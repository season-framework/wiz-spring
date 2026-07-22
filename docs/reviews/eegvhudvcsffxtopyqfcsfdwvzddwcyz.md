# 장기 실행·빌드 캐시·Git 마이그레이션 점검

- 리뷰 ID: `eegvhudvcsffxtopyqfcsfdwvzddwcyz`
- 기준 runtime: WIZ Spring 0.2.6 / 변경 후보: 0.2.7
- 실서비스 표본: `/root/workspace/kreonet` (읽기 전용 점검)

## 확인 결과

2026-07-22에 약 20시간 실행된 KREONET Java process를 점검했을 때 RSS는 약 333 MiB였지만, G1 heap은 80 MiB 중 약 56 MiB만 사용했고 5초 표본에서 추가 GC가 발생하지 않았다. metaspace 사용량은 약 63 MiB, thread 55개, open FD 74개, 삭제된 파일을 붙잡은 FD는 0개였다. 이 한 번의 표본만으로 장기 누수를 배제할 수는 없지만, 당시의 심한 지연을 heap 또는 FD cache 누적으로 설명할 직접 근거는 없었다.

반면 운영 로그에는 build marker가 바뀔 때 Hikari pool을 닫은 직후 여러 Tomcat worker가 `NoClassDefFoundError: ch/qos/logback/classic/spi/ThrowableProxy`로 종료된 기록이 반복됐다. 기존 구현에는 다음 두 경쟁 조건이 있었다.

1. 새 요청이 marker 변경을 발견하면 다른 요청이 아직 쓰는 project classloader와 `onClose` resource를 즉시 닫았다.
2. 새 project classloader의 parent를 호출 thread의 context classloader로 정해, warmup과 HTTP reload에서 서로 다른 parent 또는 이전 project loader chain이 만들어질 수 있었다.

이번 변경은 project runtime을 요청 단위 lease로 유지한다. marker 변경 후 새 요청은 새 snapshot을 사용하고, 이전 Hikari pool/classloader/snapshot은 해당 버전을 사용하는 마지막 요청이 끝난 뒤 닫힌다. parent는 WIZ Spring을 실제로 load한 고정 classloader로 정했다. `bundle/classes`, JAR/lib, app/route/config metadata는 `.wiz/runtime-snapshots/<pid>/...`에 hard-link하고 hard-link가 불가능한 filesystem에서는 복사한다. runtime loader는 `.wiz/build.lock`을 비차단 확인하고 snapshot 전후의 atomic marker token을 재검증한다. build 중이거나 marker가 바뀌면 새 후보를 폐기하고 마지막 정상 runtime을 유지한다. 표준 WIZ build는 기존 `bundle/`을 먼저 unlink하고 새 파일을 생성하므로 snapshot inode는 변하지 않는다. 마지막 lease가 닫히면 snapshot을 삭제하고, 다음 시작 시 살아 있지 않은 PID의 잔여 snapshot도 정리한다.

`bundle/` 파일을 외부 도구가 `--inplace` 방식으로 덮어쓰는 것은 이 불변성 계약에 포함되지 않는다. 배포에는 반드시 `wiz-spring build` 또는 새 release directory를 사용한다.

## 디스크 사용량 해석

KREONET workspace는 총 약 2.6 GiB였고 큰 항목은 다음과 같았다.

| 경로 | 점검 당시 크기 | 성격 |
| --- | ---: | --- |
| `.wiz/npm-cache` | 933 MiB | npm의 content-addressable download cache. build 결과가 아니며 자동으로 작아지지 않는다. |
| `build` | 665 MiB | 이 중 Angular `node_modules` 363 MiB, staging source 200 MiB 이상이다. 한 세대의 작업 tree가 큰 것이며 build 세대가 계속 쌓인 형태는 아니었다. |
| `refer` | 656 MiB | Git에서 제외된 개발 참고자료이며 313 MiB zip을 포함한다. runtime과 무관하다. |
| `bundle` | 139 MiB | 실행 bundle. `src/assets`가 약 102 MiB다. |
| `.git` | 63 MiB | 일부 대형 binary history를 포함한다. |

`src/assets`, build staging, Angular staging, runtime bundle에는 같은 대형 asset이 각각 존재할 수 있으므로 `du` 합계가 커진다. 이는 과거 build 세대 누적과 구분해야 한다. mutable 문서·업로드 파일은 장기적으로 Git source가 아니라 persistent storage/object storage로 분리하는 편이 낫다.

Maven dependency는 기존 일반 build에서 `build/target/dependency`를 비우지 않아 pom에서 제거되거나 version이 바뀐 JAR가 남을 수 있었다. 이제 `.wiz/build.lock`으로 같은 workspace의 여러 build process를 직렬화하고, `.dependency-next`에 먼저 resolve한 뒤 직전 정상본을 backup한 상태에서 directory를 교체한다. pom이 없어지면 이전 dependency output도 제거하며, resolve 또는 교체가 실패하면 가능한 한 현재 정상 dependency set을 복원한다.

새 workspace는 `src/angular/package-lock.json`을 포함하고 Git에서 추적한다. 따라서 clean build가 `npm ci`를 사용해 서버별 dependency drift와 불필요한 cache 증가를 줄인다. 기존 workspace도 다음을 한 번 수행해 lockfile을 커밋해야 한다.

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

release 방식 도입 전의 최소 안전 절차는 아래 순서다. 이 방식은 build 시간만큼 downtime이 생기지만, 실행 중 `bundle/`이 부분 교체되어 static file이 404가 되거나 runtime version이 섞이는 일을 피한다.

```bash
systemctl stop wiz.kreonet
git -C /root/workspace/kreonet pull --ff-only
wiz-spring build --root /root/workspace/kreonet --clean
systemctl start wiz.kreonet
curl --fail http://127.0.0.1:8080/actuator/health
```

`build/`, `bundle/`, `.wiz/`, log, local config, database/upload data는 Git으로 옮기지 않는다. 동일한 runtime JAR version, Java/Node/Maven 조건, source와 lockfile, secret/config, persistent data를 각각 명시적으로 이관한다. 대형 binary history가 계속 늘면 Git LFS 또는 artifact/object storage를 적용한다.

## 운영 점검과 정리

- heap 증가 여부는 같은 부하 조건에서 `jcmd <pid> GC.heap_info`, RSS, loaded classloader 수를 시간 순서로 비교한다. 한 시점의 RSS만으로 cache leak로 판단하지 않는다.
- `find /proc/<pid>/fd -type l`과 `(deleted)` target을 함께 확인해 log rotation 또는 삭제 파일 FD 누수를 찾는다.
- `.wiz/npm-cache`는 build가 실행 중이지 않을 때 `npm cache verify --cache <workspace>/.wiz/npm-cache`로 검사한다. 용량 회수가 필요하면 service/build를 멈추고 cache를 별도 경로로 이동한 뒤 clean build를 검증하고 삭제한다.
- `build/`와 `bundle/`을 service 실행 중 수동 삭제하지 않는다. source/lockfile로 재생성 가능한 산출물이지만 현재 process가 `bundle/`의 static file을 직접 제공한다.
- `.wiz/runtime-snapshots`는 정상 request 종료와 process 종료 시 자동 정리된다. 강제 종료로 남은 directory는 다음 process가 dead PID를 확인한 뒤 정리한다.

남은 운영 리스크는 in-place build 중 static bundle 교체, 기본 in-memory HTTP session의 restart 유실, 단일 JVM socket room registry, application 자체의 DB/외부 I/O 지연이다. 무중단 배포나 scale-out이 필요하면 release/blue-green 전환과 Redis/JDBC session, 외부 broker를 별도로 구성해야 한다.
