# 장기 실행·빌드 캐시·Git 마이그레이션 점검

- 리뷰 ID: `eegvhudvcsffxtopyqfcsfdwvzddwcyz`
- 실서비스 표본 runtime: WIZ Spring 0.2.6
- 검증·릴리스 runtime: WIZ Spring 0.2.7
- 실서비스 표본: `/root/workspace/kreonet` (읽기 전용 점검)

반복 build 최적화의 정량 비교와 재현 방법은 [별도 성능 비교 문서](eegvhudvcsffxtopyqfcsfdwvzddwcyz-performance.md)에 기록했다.

## 확인 결과

2026-07-22에 약 20시간 실행된 KREONET Java process를 점검했을 때 RSS는 약 333 MiB였지만, G1 heap은 80 MiB 중 약 56 MiB만 사용했고 5초 표본에서 추가 GC가 발생하지 않았다. metaspace 사용량은 약 63 MiB, thread 55개, open FD 74개, 삭제된 파일을 붙잡은 FD는 0개였다. 이 한 번의 표본만으로 장기 누수를 배제할 수는 없지만, 당시의 심한 지연을 heap 또는 FD cache 누적으로 설명할 직접 근거는 없었다.

반면 운영 로그에는 build marker가 바뀔 때 Hikari pool을 닫은 직후 여러 Tomcat worker가 `NoClassDefFoundError: ch/qos/logback/classic/spi/ThrowableProxy`로 종료된 기록이 반복됐다. 기존 구현에는 다음 두 경쟁 조건이 있었다.

1. 새 요청이 marker 변경을 발견하면 다른 요청이 아직 쓰는 project classloader와 `onClose` resource를 즉시 닫았다.
2. 새 project classloader의 parent를 호출 thread의 context classloader로 정해, warmup과 HTTP reload에서 서로 다른 parent 또는 이전 project loader chain이 만들어질 수 있었다.

이번 변경은 project runtime을 요청 단위 lease로 유지한다. marker 변경 후 새 요청은 새 snapshot을 사용하고, 이전 Hikari pool/classloader/snapshot은 해당 버전을 사용하는 마지막 요청이 끝난 뒤 닫힌다. parent는 WIZ Spring을 실제로 load한 고정 classloader로 정했다. `bundle/classes`, JAR/lib, app/route/config metadata는 workspace 밖의 `<cache-base>/workspaces/<workspace-hash>/runtime-snapshots/<host>/<pid-start>/...`에 hard-link하고 hard-link가 불가능한 filesystem에서는 복사한다. runtime loader는 같은 workspace key의 외부 `build.lock`을 비차단 확인하고 snapshot 전후의 atomic marker token을 재검증한다. lock을 읽을 수 없으면 마지막 정상 runtime만 유지하고 정상본도 없으면 fail-closed한다. build 중이거나 marker가 바뀌면 새 후보를 폐기하고 마지막 정상 runtime을 유지한다. 표준 WIZ build는 `build/target/work/bundle-next`에서 fallback, dependency manifest, CycloneDX BOM과 완료 marker까지 준비한 뒤 직전 bundle을 backup하고 rename으로 게시한다. 선행 단계나 조립이 실패하면 clean/phase와 관계없이 직전 정상 bundle을 유지하며, snapshot inode도 기존 요청이 끝날 때까지 변하지 않는다. 비정상 종료로 staging/backup이 남으면 다음 build가 직전 bundle을 복구하고 잔여물을 정리한다. 마지막 lease와 정상 process 종료 때 snapshot을 삭제하고, 다음 시작 시 host/machine 및 process 시작 시각 namespace를 확인해 같은 host의 모든 workspace key에서 죽은 process 잔여 snapshot을 정리한다.

실제 반복 교체를 진단했을 때는 Java API build 6회 후 retired `ProjectRuntimeClassLoader` 6개가 남아 총 7개가 됐고 metaspace 사용량이 약 294 MiB, RSS가 약 773 MiB까지 증가했다. heap dump의 GC root는 부모 Spring의 `BridgeMethodResolver` 및 `BeanAnnotationHelper` soft cache였고, project loader마다 등록된 SQLite JDBC driver도 별도로 남았다. runtime 종료 시 Spring/JDK reflection cache를 비우고 child loader가 자기 JDBC driver를 해제한 뒤 loader를 닫도록 보강했다. 한 driver의 cleanup callback이 실패해도 나머지 driver 정리는 계속한다. 같은 시나리오를 다시 실행한 뒤 Full GC에서는 project loader, `DriverInfo`, `org.sqlite.JDBC`, SQLite native mapping이 각각 1개였고 metaspace 사용량은 약 87.6 MiB였다. Spring 내부 cache 이름은 framework 내부 구현이므로 현재 Spring 버전의 단위 테스트로 보호하며, 향후 Spring upgrade 때 재검증해야 한다.

build marker에는 frontend와 분리된 `runtimeDigest`도 기록한다. app API/classes, dependency, config, app/route runtime metadata가 같으면 화면·정적 파일 build timestamp가 바뀌어도 실행 중인 JPA/classloader를 재생성하지 않는다. 반대로 Java API, dependency, config 또는 runtime metadata가 바뀌면 새 runtime을 load한다. 완료 marker가 게시 과정에서 잠시 보이지 않을 때는 마지막 `marker:` 또는 `runtime:` runtime을 유지한다.

build lock은 명시한 `WIZ_SPRING_RUNTIME_DIR` 또는 안정적인 `~/.local/state/wiz-spring/runtime`에, 대용량 snapshot은 `WIZ_SPRING_CACHE_DIR` 또는 `~/.cache/wiz-spring`에 둔다. 따라서 systemd에 없는 `XDG_RUNTIME_DIR`이나 용량이 제한된 tmpfs에 기본 의존하지 않고 workspace에도 숨김 framework 디렉터리를 생성하지 않는다. 생성되는 systemd unit은 workspace 소유자를 기본 `User=`로 지정하며 build CLI도 그 사용자로 실행해야 같은 owner-only lock을 공유한다. 등록 시 runtime/cache/state 환경은 unit에 고정되고 서비스별 log directory를 사용한다. MCP 상태도 workspace가 아니라 `WIZ_SPRING_STATE_DIR`, `XDG_STATE_HOME/wiz-spring`, `~/.local/state/wiz-spring` 순서의 외부 사용자 state에 file lock과 atomic replace로 저장한다. runtime/cache/state 환경 변수와 `mcp --state`는 workspace 내부 경로를 가리키면 거부한다.

`bundle/` 파일을 외부 도구가 `--inplace` 방식으로 덮어쓰는 것은 이 불변성 계약에 포함되지 않는다. 배포에는 반드시 `wiz-spring build` 또는 새 release directory를 사용한다.

## 실제 0.2.7 검증

최종 executable JAR로 임시 workspace에 `create`를 실행해 실제 Maven 28개 JAR, Boot classpath 632개 파일, npm 486개 package와 Angular production bundle을 포함한 초기 clean build를 완료했다. 빈 port `42001`에서 실행해 `/actuator/health`, root/dashboard 화면, 로그인, dashboard/members/portal API, `/v3/api-docs`, `/v3/api-docs.yaml`, `/swagger-ui.html`을 확인했다.

화면 marker를 V1부터 V4까지 바꾸고 Java API 응답 marker를 V1부터 V6까지 바꾸며 build와 HTTP 호출을 반복했다. 최종 화면 변경 build는 내부 13.03초, 전체 wall 14.29초였고 `runtimeDigest` 및 runtime load/retire 횟수가 바뀌지 않았다. API 변경 6회의 내부 build는 13.03~13.30초, 전체 wall은 14.19~14.50초였으며 매번 새 응답이 즉시 반영됐다. 완료 marker를 잠시 다른 이름으로 옮긴 동안에도 마지막 정상 API가 유지됐고 불필요한 runtime reload는 없었다.

workspace POM에 `commons-text`를 추가해 실제 Java API에서 호출한 뒤 반복 cache hit와 제거를 확인했고, Angular package에는 `decimal.js`를 추가해 실제 import/bundle 반영, 반복 cache hit, 제거 후 stale package 부재를 확인했다. dependency 변경 build도 모두 성공했으며 source, `build`, `bundle` 어디에도 `.wiz` directory가 생성되지 않았다. Maven 전체 테스트 278개, package 및 `--version` 검증도 통과했다.

## 디스크 사용량 해석

KREONET workspace는 총 약 2.6 GiB였고 큰 항목은 다음과 같았다.

| 경로 | 점검 당시 크기 | 성격 |
| --- | ---: | --- |
| npm cache | 933 MiB | 점검 당시 이전 구현이 workspace 내부에 둔 content-addressable download cache다. 현재 구현은 npm 기본 사용자 cache를 공유하며 workspace에는 만들지 않는다. |
| `build` | 665 MiB | 이 중 Angular `node_modules` 363 MiB, staging source 200 MiB 이상이다. 한 세대의 작업 tree가 큰 것이며 build 세대가 계속 쌓인 형태는 아니었다. 현재 staging 경로는 `build/target/work/source`이며 일반 build는 `node_modules` 내부를 순회하지 않고 Angular 증분 cache와 함께 보존한다. |
| `refer` | 656 MiB | Git에서 제외된 개발 참고자료이며 313 MiB zip을 포함한다. runtime과 무관하다. |
| `bundle` | 139 MiB | 실행 bundle. `src/assets`가 약 102 MiB다. |
| `.git` | 63 MiB | 일부 대형 binary history를 포함한다. |

`src/assets`, build staging, Angular staging, runtime bundle에는 같은 대형 asset이 각각 존재할 수 있으므로 `du` 합계가 커진다. 이는 과거 build 세대 누적과 구분해야 한다. mutable 문서·업로드 파일은 장기적으로 Git source가 아니라 persistent storage/object storage로 분리하는 편이 낫다.

Maven dependency는 기존 일반 build에서 `build/target/dependency`를 비우지 않아 pom에서 제거되거나 version이 바뀐 JAR가 남을 수 있었다. 이제 workspace 정규 경로에서 계산한 외부 `build.lock`으로 같은 workspace의 여러 build process를 직렬화하고, `--package`의 source/config/pom 변경도 같은 lock 안에서 수행한다. 사용이 끝난 JVM 내부 lock entry는 제거한다. POM과 local parent, Maven Wrapper/`.mvn`, 사용자 Maven 설정, 실제 실행 파일 및 전달 환경을 fingerprint하고 게시된 JAR의 정확한 파일 집합·크기·SHA-256까지 같으면 반복 build에서 Maven process를 생략한다. workspace/local Maven model·사용자 settings·Maven option에서 확인한 SNAPSHOT, version range/LATEST/RELEASE, 실제 profile activation 및 project systemPath처럼 결과 안정성을 보장하기 어려운 입력은 cache를 우회한다. transitive JAR 이름에서 SNAPSHOT이 발견된 경우에도 다음 build에서 다시 해석한다. cache miss의 dependency는 임시 directory에 먼저 resolve한 뒤 cache manifest와 함께 직전 정상본을 backup한 상태에서 directory를 교체한다. pom이 없어지면 이전 dependency output도 제거하며, resolve 또는 교체가 실패하면 가능한 한 현재 정상 dependency set을 복원한다. `--clean`은 build 아래 cache를 제거해 다음 해석을 강제한다. javac classpath도 현재 resolve 결과와 workspace `lib/`만 사용하므로 이전 `bundle/lib`가 제거된 dependency를 대신해 compile을 통과시키지 않는다. 실행 중인 WIZ Spring이 Boot fat JAR이면 `BOOT-INF/classes`와 `BOOT-INF/lib` 추출 결과를 원본 JAR SHA-256별 외부 workspace cache에 원자 게시하고 manifest와 파일 hash 검증 후 재사용한다. 따라서 반복 build가 runtime JAR 전체를 다시 압축 해제하지 않으며 여러 fat JAR의 추출물이 서로 덮어쓰지 않는다. Java source가 0개가 되거나 frontend가 fallback으로 바뀌면 이전 class/JAR/frontend output도 새 bundle에 재사용하지 않는다.

새 workspace는 `src/angular/package-lock.json`을 포함하고 Git에서 추적한다. 따라서 clean build가 `npm ci`를 사용해 서버별 dependency drift와 불필요한 cache 증가를 줄인다. 일반 build는 package/lock/`.npmrc` fingerprint가 같으면 staged dependency를 재사용하고, dependency가 없거나 fingerprint가 달라지면 자동으로 다시 설치한다. lockfile이 없으면 semver range의 registry 결과를 고정할 수 없으므로 일반 build도 매번 `npm install`을 수행한다. lockfile이 없는 workspace는 다음을 한 번 수행해 lockfile을 커밋해야 한다.

```bash
cd <workspace>/src/angular
npm install --package-lock-only --ignore-scripts
git add package-lock.json ../../.gitignore
```

기존 `.gitignore`에 `package-lock.json`이 있으면 해당 한 줄을 먼저 제거한다. 위 `git add` 경로는 workspace 구조에 맞게 조정한다.

## 권장 Git 배포 흐름

가장 안전한 방식은 실행 중인 checkout에서 `git pull`과 build를 동시에 하지 않고, commit별 release directory에서 미리 build한 뒤 전환하는 것이다.

1. CI 또는 개발 서버에서 test를 통과한 commit과 `src/angular/package-lock.json`을 push한다.
2. 대상 서버는 `git fetch --prune` 후 commit SHA로 별도 worktree/release directory를 만든다.
3. Git에 넣지 않는 `config/application*.yml`, 환경 변수, persistent data를 release에 주입한다.
4. release directory에서 `wiz-spring build --clean`을 실행한다. JDK 21, 지원 Node.js, executable `mvnw` 또는 PATH의 Maven이 없으면 배포를 중단한다.
5. 보조 port에서 `/actuator/health`와 핵심 인증/API를 확인한다.
6. reverse proxy 또는 `current` symlink를 새 release로 원자 전환하고 service를 restart한다. 이전 release는 즉시 삭제하지 않아 rollback에 사용한다.

예시 골격은 다음과 같다. 실제 user, permission, config 경로와 proxy 전환은 운영 환경에 맞춘다.

```bash
git -C /srv/kreonet/repository fetch --prune origin
commit=$(git -C /srv/kreonet/repository rev-parse origin/main)
release=/srv/kreonet/releases/$commit
git -C /srv/kreonet/repository worktree add --detach "$release" "$commit"

install -m 600 /etc/wiz/kreonet/application.yml "$release/config/application.yml"
wiz-spring build --root "$release" --clean

# 보조 port health/smoke 확인 후 current link와 service를 전환한다.
```

release 방식 도입 전의 최소 안전 절차는 아래 순서다. 현재 build도 새 bundle을 staging에서 완성한 뒤 게시하지만, 두 directory rename 사이의 매우 짧은 경로 공백까지 제거하는 단일 원자 교환은 아니다. 아래 방식은 build 시간만큼 downtime이 생기는 대신 static 요청과 배포 전환까지 확실히 분리한다.

```bash
systemctl stop wiz.kreonet
git -C /root/workspace/kreonet pull --ff-only
wiz-spring build --root /root/workspace/kreonet --clean
systemctl start wiz.kreonet
curl --fail http://127.0.0.1:8080/actuator/health
```

`build/`, `bundle/`, log, local config, database/upload data는 Git으로 옮기지 않는다. build lock/runtime snapshot/MCP 상태는 workspace 밖의 운영체제 runtime/state에 있고 npm cache도 사용자 cache이므로 release checkout에 복사하지 않는다. 동일한 runtime JAR version, Java/Node/Maven 조건, source와 lockfile, secret/config, persistent data를 각각 명시적으로 이관한다. 대형 binary history가 계속 늘면 Git LFS 또는 artifact/object storage를 적용한다.

## 운영 점검과 정리

- heap 증가 여부는 같은 부하 조건에서 `jcmd <pid> GC.heap_info`, RSS, loaded classloader 수를 시간 순서로 비교한다. 한 시점의 RSS만으로 cache leak로 판단하지 않는다.
- `find /proc/<pid>/fd -type l`과 `(deleted)` target을 함께 확인해 log rotation 또는 삭제 파일 FD 누수를 찾는다.
- npm cache 위치는 `npm config get cache`로 확인하고 `npm cache verify`로 검사한다. 이는 사용자 단위 공유 cache이며 workspace 또는 release와 함께 복사하지 않는다.
- `build/`와 `bundle/`을 service 실행 중 수동 삭제하지 않는다. source/lockfile로 재생성 가능한 산출물이지만 현재 process가 `bundle/`의 static file을 직접 제공한다.
- 외부 runtime state의 `runtime-snapshots`는 정상 request 종료와 process 종료 시 자동 정리된다. 강제 종료로 남은 directory는 다음 process가 dead PID를 확인한 뒤 정리한다.

남은 운영 리스크는 bundle publish의 두 rename 사이에 생길 수 있는 짧은 static 경로 공백, 기본 in-memory HTTP session의 restart 유실, 단일 JVM socket room registry, application 자체의 DB/외부 I/O 지연이다. npm 재사용 key에는 project package/lock/`.npmrc`만 포함되므로 같은 workspace에서 Node/npm 실행 버전 또는 사용자 `~/.npmrc`를 바꾼 경우에는 clean build로 `node_modules`를 재생성해야 한다. 최종 template lockfile의 `npm audit`에는 Angular CLI가 개발 도구로 사용하는 MCP/Hono 경로의 Windows static path traversal 관련 moderate 항목 3건도 남아 있다. 완전한 무중단 배포나 scale-out이 필요하면 versioned bundle 또는 release/blue-green 전환과 Redis/JDBC session, 외부 broker를 별도로 구성하고, Angular CLI가 수정된 transitive dependency를 채택하면 lockfile을 갱신해야 한다.
