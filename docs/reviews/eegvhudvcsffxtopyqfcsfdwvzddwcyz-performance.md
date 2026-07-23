# 반복 build 성능 비교

- 리뷰 ID: `eegvhudvcsffxtopyqfcsfdwvzddwcyz`
- 측정 시각: 2026-07-23 16:04 KST
- 이전 기준: Git `0b43f4d016259820968db7335c2b806f88b69aad`
- 이후 기준: WIZ Spring `0.2.7` 릴리스 소스

## 결과

시간은 같은 host에서 예열 후 측정한 wall clock의 `중앙값 / p95`다. 감소율이 양수면 이후 구현이 빠르고, 음수면 느리다. Maven·Boot는 버전별 10회, Angular는 버전별 5회 측정했다.

| 반복 build 시나리오 | 이전 | 이후 | 감소율 |
| --- | ---: | ---: | ---: |
| Maven dependency fixture 전체 wall | 3.272초 / 3.495초 | 1.333초 / 1.389초 | 59.3% |
| Boot classpath fixture 전체 wall | 2.113초 / 2.246초 | 2.186초 / 2.311초 | -3.5% |
| Angular bundle fixture 전체 wall | 12.012초 / 12.226초 | 11.883초 / 12.170초 | 1.1% |

| 세부 단계 | 이전 | 이후 | 감소율 | 판정 |
| --- | ---: | ---: | ---: | --- |
| Maven `app-dependencies` | 2.080초 / 2.320초 | 125.0ms / 146.0ms | 94.0% | 유효한 개선 |
| Boot `java-compile` 전체 | 838.5ms / 865.0ms | 852.5ms / 914.0ms | -1.7% | 시간 개선 확인 안 됨 |
| Angular tree `reconstruct` | 541.0ms / 606.0ms | 222.0ms / 243.0ms | 59.0% | 유효한 개선 |
| Angular `frontend` | 10.180초 / 10.360초 | 10.300초 / 10.620초 | -1.2% | 오차 범위 수준 |

Maven cache는 이후 표본 10/10에서 hit했고, Maven process를 매번 실행하던 이전 구현보다 dependency 단계 중앙값이 94.0% 줄었다. JVM 시작 시간을 포함한 전체 wall에서도 59.3% 줄었다.

Angular 일반 build는 양쪽 모두 lockfile 기반 `node_modules`를 재사용했다. 이후 구현의 `reconstruct`는 보존할 cache 아래를 전부 순회하지 않아 59.0% 빨라졌다. `.angular/cache`도 보존하지만 이 fixture의 Angular CLI 실행 시간은 1.2% 느려, 증분 compiler 자체의 시간 개선으로 주장할 수 없다. 전체 wall의 1.1% 차이도 주로 reconstruct와 작은 후처리 단계에서 나온 값이다.

Boot cache는 이후 표본 10/10에서 hit해 약 36.5 MiB runtime JAR의 반복 압축 해제는 제거했다. 다만 현재 로그는 classpath 준비만 따로 재지 않으므로 표의 값은 digest·manifest 검증, `javac`, `app-api.jar` packaging을 합친 `java-compile` 전체다. 따뜻한 filesystem cache 조건에서는 중앙값이 1.7% 느렸으므로 현재 결과만으로 시간 성능 개선이라 평가하지 않는다.

## 측정 방법

[`scripts/benchmark-build-performance.mjs`](../../scripts/benchmark-build-performance.mjs)는 이전/이후 executable JAR와 fixture, runtime/cache/state directory를 각각 격리한다. 각 시나리오는 버전별 1회 예열한 뒤 홀수 표본은 이전→이후, 짝수 표본은 이후→이전 순서로 교차 실행한다. 실패, 필수 phase 누락, 예상 cache miss, Angular fallback, 필수 산출물 누락이 있으면 표를 만들지 않고 종료한다.

| fixture | 격리하려는 동작 | 예열·반복 조건 |
| --- | --- | --- |
| Maven | Java source 없이 안정 버전 `sqlite-jdbc:3.49.1.0` 해석 | 공유 local Maven repository가 이미 따뜻한 반복 build |
| Boot | workspace POM 없이 Java API 1개 compile | 같은 runtime JAR classpath를 예열 후 반복 |
| Angular | 기본 project template에서 Maven/Java 제거 | 양쪽 모두 clean 1회 후 normal bundle 반복 |

측정 host는 Linux 6.8 x86_64, 4 CPU, 15.4 GiB RAM, OpenJDK 21.0.11, Maven 3.8.7, Node.js 24.15.0이었다. 비교 JAR는 다음 digest로 고정했다.

| 구분 | 크기 | SHA-256 |
| --- | ---: | --- |
| 이전 | 38,150,315 bytes | `f9c70dc52d2555e37d18086ea2b8e33719e35a30ec8fcd0908a20637dd30e654` |
| 이후 | 38,237,081 bytes | `ea4a4766129861bba4f32d53931655e191fcd17ca02f9e6ea587056bef889e66` |

재측정 명령은 다음과 같다. 이전/이후 JAR는 각각 해당 source tree에서 `./mvnw -q -DskipTests package`로 먼저 생성한다.

```bash
node scripts/benchmark-build-performance.mjs \
  --before-jar /path/to/before/wiz-spring-0.2.7.jar \
  --after-jar target/wiz-spring-0.2.7.jar \
  --iterations 10 \
  --include-angular \
  --angular-iterations 5 \
  --baseline 0b43f4d016259820968db7335c2b806f88b69aad \
  --candidate "WIZ Spring 0.2.7 release" \
  --output target/build-performance-benchmark.json
```

## 해석 범위와 남은 측정

- 이 수치는 warm 반복 build 비교다. 최초 dependency download, cold disk cache, SNAPSHOT/dynamic Maven 입력은 포함하지 않는다.
- 표본 수가 작아 nearest-rank p95는 사실상 관측 상단값이다. 회귀 gate를 만들 때는 표본을 20회 이상으로 늘리고 median 기준 허용 오차를 먼저 정해야 한다.
- Boot classpath 준비만의 시간과 read/write byte를 별도 계측하지 않았다. 더 큰 runtime JAR, cold cache, HDD/NFS에서도 비교해야 cache의 I/O 이점을 판단할 수 있다.
- KREONET 전체 source/assets, 동시 build lock 경합, bundle 게시의 peak disk 사용량, 장기 runtime RSS·HTTP 지연은 이 benchmark 범위가 아니다.
- bundle staging/backup과 runtime snapshot은 실패 시 정상본 보존을 위한 변경이다. 별도 측정 전에는 성능 개선으로 분류하지 않는다.
- 이후 JAR digest는 `0.2.7` 릴리스 소스의 최종 코드로 고정했다. 코드나 build dependency가 바뀌면 같은 절차로 다시 측정해야 한다.
