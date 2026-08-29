# WIZ Spring 1.0 아키텍처 재구성 및 1.1 적용 기록

- 리뷰 ID: `sdbnyfqrfknmzwgvuaodnbwozqjwwkon`
- 대상: WIZ Spring `1.0.0` 기준선과 `1.1.0` 생성 프로젝트의 backend source 구조
- 상태: `1.1.0` 템플릿 적용 및 검증 완료

## 결론

권장 구조는 **WIZ-shaped standard Spring**이다. `1.0`에서 확보한 표준 Maven source,
Spring bean/transaction, frontend-backend 분리, resource API는 유지하고 애플리케이션
탐색 구조만 아래처럼 단순화한다.

```text
HTTP -> Controller -> Root Struct -> Feature Struct -> Repository 또는 JdbcClient -> DB
```

Python WIZ에서 되살릴 것은 `Struct`라는 type-safe한 domain 진입점과 `model` 중심의
탐색 방식이다. `portal`의 재사용 의도는 Spring bean과 명시적 계약을 사용하는
`module`로 다시 정의한다. 다시 도입하지 않을 것은 페이지별 `api.py`, 문자열 기반
`wiz.model(...)`, 동적 module loading, 전역 request state, build-time Java source
rewrite다.

## 확인한 현재 기준선

확인 대상은 현재 `main`의 1.0 template, `0.2.9` tag의 Java WIZ template,
`../wiz-sample-project`의 Python sample이다.

- 1.0 공통 backend template에는 production Java 파일 36개가 8개 package에 있다.
- 이 중 모든 프로젝트에 필요한 기반 파일은 5개이고, fresh project에만 들어가는
  sample backend 파일은 31개다. `--path`와 `--uri` import에는 이 31개가 주입되지 않는다.
- 1.0 sample에는 `dao` package가 없다. 실제 분산 지점은 `api/model` 7개,
  `service` 7개, `domain` 5개, `repository` 3개와 controller들이다.
- 사용자·인증·프로필 영역만 보아도 controller 3개, API model 3개, service 2개,
  domain 2개, repository 1개로 관련 구현 11개가 5개 package에 분산된다.
- Python sample의 프로젝트 공통 `controller`와 `model` 영역은 6개 Python 파일이며,
  `Struct -> User sub-struct -> DB model`의 짧은 탐색 경로를 사용한다. 단, 페이지별
  API와 동적 dictionary 계약까지 포함하므로 Java 파일 수와 직접적인 생산성 비교값은
  아니다.

따라서 문제의 핵심은 “Spring이라서 DAO가 필수”인 것이 아니라, 현재 sample이
기술 계층별 package를 기본값으로 보여 주어 하나의 기능을 여러 폴더에서 수정하게
만든다는 점이다.

## 설계 원칙

1. **표준 Spring은 유지한다.** Java source는 `src/main/java`, resource는
   `src/main/resources`, build는 Maven Wrapper가 직접 담당한다.
2. **하나의 개념에는 하나의 계층 이름만 둔다.** 비즈니스 orchestration은
   `*Struct`가 담당하며 같은 기능에 `*Service`를 추가하지 않는다.
3. **model은 업무 단위로 한 단계만 묶는다.** `model` root에는 전체 facade인
   `Struct.java`만 두고 나머지는 `model/{feature}`에 둔다.
   `model/{feature}/entity` 같은 세 번째 계층은 만들지 않는다.
4. **기술 계층보다 변경 단위를 가까이 둔다.** 기본 기능은 Controller와 Feature
   Struct로 시작한다. JPA가 필요하면 Entity와 Spring Data Repository를 같은
   `model/{feature}` 또는 같은 `module/{feature}`에 둔다.
5. **타입 안전성을 버리지 않는다.** persistence entity를 JSON으로 직접 노출하지
   않고, endpoint 전용 입력은 Controller의 nested record, 안전한 domain 출력은
   Struct의 nested record로 둔다.
6. **암시적 runtime 동작을 만들지 않는다.** 모든 의존성은 constructor injection으로
   연결하고 transaction, validation, endpoint mapping은 Spring annotation으로 보인다.
7. **포괄적인 기반 package를 만들지 않는다.** `support`, `common`, `util` 대신
   `config`, `security`, `exception`, `web`처럼 책임을 이름으로 드러낸다.
8. **module은 실제 재사용 경계일 때만 만든다.** 프로젝트에 종속된 기능은
   `model/{feature}`에 유지하고, 명시적 public contract와 독립 테스트가 가능한 기능만
   `module/{feature}`로 승격한다.

## 권장 source 구조

### 기본 구조

업무 객체는 `model` 아래에서 기능·aggregate 단위로 한 번 묶는다. Java package root는
제외하고 `model`을 첫 단계로 보아 `model/user`까지만 허용하며,
`model/user/entity`처럼 더 내려가지는 않는다.

```text
src/main/java/com/example/app/
├── Application.java
├── controller/
│   ├── UserController.java          # auth, members, profile endpoint 묶음
│   ├── DashboardController.java
│   ├── PostController.java
│   └── ChatController.java
├── model/
│   ├── Struct.java                  # 전체 domain의 typed facade
│   ├── user/                         # 사용자 aggregate
│   │   ├── UserStruct.java          # use case + transaction
│   │   ├── UserEntity.java          # JPA를 선택한 경우에만
│   │   ├── UserRepository.java      # JPA를 선택한 경우에만
│   │   └── UserRole.java
│   ├── post/                         # 게시물 aggregate
│   │   ├── PostStruct.java
│   │   ├── PostEntity.java
│   │   ├── PostRepository.java
│   │   └── PostStatus.java
│   ├── chat/                         # 채팅 기능
│   │   ├── ChatStruct.java
│   │   ├── ChatMessageEntity.java
│   │   ├── ChatMessageRepository.java
│   │   └── ChatEventHub.java
│   └── dashboard/                    # 여러 aggregate를 읽는 조회 모델
│       └── DashboardStruct.java
├── config/
│   ├── ApiProperties.java
│   ├── ApiConfiguration.java
│   ├── SampleBackendConfiguration.java
│   └── SampleDataInitializer.java
├── security/
│   └── SessionContext.java
├── exception/
│   ├── ApiException.java
│   └── ApiExceptionHandler.java
└── web/
    ├── ApiController.java            # 중앙 API prefix annotation
    └── RuntimeConfigurationController.java
```

`user`, `post`, `chat`은 화면 이름이나 기술 계층이 아니라 함께 변경되는 업무 객체의
경계다. 한 그룹이 커지면 `model/user/auth`로 더 깊게 만들지 않고 `model/account`,
`model/auth`처럼 같은 깊이의 독립된 업무 그룹으로 나눈다. 각 그룹 안에서는
`Entity`, `Repository`, `Struct`, 공유 record가 서로 가까이 있어 객체의 데이터와
행위를 한 번에 파악할 수 있다.

`config`, `security`, `exception`, `web`은 `support`를 이름만 바꾼 단일 catch-all이
아니다. Spring 설정, 인증 context, 예외 계약, HTTP 기반 기능이라는 서로 다른 책임을
각각 나타낸다. 해당 책임의 class가 없으면 빈 package도 만들지 않는다.

테스트도 production package를 그대로 따라 `controller/UserControllerTest`,
`model/user/UserStructTest`처럼 두고 별도 `unit`, `integration`, `repository` 하위
directory를 관례적으로 추가하지 않는다.

### Controller 이름의 의미

Python WIZ의 `src/controller/base.py`, `user.py`, `admin.py`는 HTTP endpoint가 아니라
요청 전처리·인증 chain이었다. 표준 Spring에서 `controller`는 Spring MVC endpoint라는
일반적인 의미로 사용한다. 기존 전처리 chain의 역할은 `SessionContext`, Spring Security,
`HandlerInterceptor` 또는 명시적인 policy annotation으로 옮기고 `ControllerHook`을 다시
만들지 않는다. 즉, 이름은 익숙하게 유지하되 runtime 의미는 Spring에 맞춘다.

### module 구조: portal 대체

`portal`은 UI portal, 외부 gateway, 재사용 library 중 무엇을 뜻하는지 이름만으로 알기
어렵다. 새 이름은 `module`로 정한다. `plugin`은 실행 중 설치·해제되는 확장이라는 오해를
만들고, `feature`는 이미 `model/{feature}`가 나타내는 프로젝트 내부 업무 단위와
겹치므로 사용하지 않는다.

`model/{feature}`와 `module/{feature}`의 차이는 재사용성이다.

| 위치 | 의미 | 생명주기 |
| --- | --- | --- |
| `model/post` + `controller/PostController` | 현재 application에 종속된 일반 기능 | application과 항상 함께 변경·배포 |
| `module/post` | 명시적 계약으로 격리된 재사용 backend 기능 | 독립 테스트 가능, 필요하면 별도 JAR로 승격 |

같은 기능을 두 위치에 중복해서 두지 않는다. post가 module 승격 기준을 만족하면
Controller, Struct, Entity, Repository를 함께 `module/post`로 옮긴다.

module 승격에는 다음 조건이 모두 필요하다.

- 두 번째 application에서 재사용하거나 독립 version·선택적 배포가 필요한 구체적
  요구가 있다.
- application의 `controller`, `model` 구현 type을 직접 참조하지 않고 작은 public
  operations/event 계약으로 연결할 수 있다.
- module 단독 Spring context test를 작성할 수 있고 설정 namespace와 transaction
  경계가 명확하다.

```text
module/post/
├── PostOperations.java         # 다른 module에 공개할 동기 계약, 필요할 때만
├── PostController.java         # project-local module이 소유한 HTTP endpoint
├── PostStruct.java             # use case + transaction
├── PostEntity.java             # persistence를 사용할 때만
├── PostRepository.java         # persistence를 사용할 때만
├── PostProperties.java         # 외부 설정이 있을 때만
├── PostEvents.java             # 외부에 발행할 event가 있을 때만
└── package-info.java           # Spring Modulith 경계 선언 시에만
```

module도 `module/post` 한 단계에서 끝낸다. `module/post/controller`,
`module/post/repository` 같은 기술 하위 package는 만들지 않는다. 기본 class 두 개를
강제하지도 않는다. 사용하지 않는 계약, 설정, event 파일은 생성하지 않는다. public
type은 `PostOperations`와 외부 event처럼 실제 계약으로 제한하고 구현 type은 가능한 한
package-private로 둔다. 얕은 단일 package에서 public이어야 하는 Entity/Repository까지
검사해야 한다면 별도 ArchUnit rule을 추가한다.

Root `Struct`는 module registry가 아니다. application 내부 기능만 typed accessor로
조합하고, module은 자기 Controller 또는 공개 Operations bean을 통해 진입한다. 문자열
module 이름, reflection scan 결과, nullable accessor로 optional module을 노출하지 않는다.

#### Spring을 활용하는 방식

| Spring 기능 | module 적용 규칙 |
| --- | --- |
| Constructor injection | 문자열 registry 대신 필요한 bean을 type으로 연결 |
| `@ConfigurationProperties` | `app.modules.post.*`처럼 module별 typed 설정을 제공 |
| Transaction | 상태 변경 transaction은 `PostStruct`가 소유 |
| Application event | 다른 module의 후속 작업에는 Entity/Repository 직접 참조 대신 domain event 사용 |
| `@TransactionalEventListener` | commit 이후 실행되어야 하는 부가 작업에 사용. 동기 결과가 필요한 호출은 interface를 직접 사용 |
| Auto-configuration | 실제로 다른 프로젝트에서 재사용될 때 별도 JAR의 `@AutoConfiguration`으로 승격 |
| Conditional bean | 외부 JAR에서 `@ConditionalOnClass`, `@ConditionalOnMissingBean`, property condition으로 기본 구현과 override 제공 |
| Test/observability | module 단위 context test, 선택적 Spring Modulith 경계 검증, Actuator health/metric 제공 |

프로젝트 내부의 항상 활성화되는 module은 일반 component scan과 constructor injection만
사용한다. 켜기/끄기 flag를 위해 모든 bean에 condition을 붙이지 않는다. 선택적 활성화와
다른 프로젝트의 override가 실제 요구가 되면 module을 별도 Maven JAR로 분리하고 Spring
Boot auto-configuration으로 제공한다. Spring Boot는 외부 JAR의 `@AutoConfiguration`,
조건부 bean, `AutoConfiguration.imports`, `ApplicationContextRunner` 기반 테스트 방식을
[공식적으로 제공한다](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html).

별도 JAR로 분리할 때는 application package의 `web.ApiController`에 의존하는 Controller를
core module에 그대로 넣지 않는다. HTTP 계약까지 재사용해야 하면 application이 얇은
Controller adapter를 소유하거나 별도 web auto-configuration artifact로 분리한다. domain
core와 특정 application의 API prefix 정책을 결합하지 않는다.

module 간에 즉시 결과가 필요한 command/query는 `PostOperations` 같은 작은 public
interface로 호출한다. 알림·감사 로그·검색 색인처럼 주 transaction의 결과를 소비하는
기능은 Spring application event로 분리한다. Spring event는 기본적으로 동기 실행이므로
transaction 범위와 실패 전파를 의도적으로 선택해야 한다. commit 이후 비동기 처리가
필요하면 `@TransactionalEventListener(phase = AFTER_COMMIT)`를 사용하되, 유실 허용이
불가능한 작업은 단순 async listener가 아니라 durable event registry나 message broker를
선택한다. 이 경계는
[Spring Modulith event 지침](https://docs.spring.io/spring-modulith/reference/events.html)과
같은 방향이다.

module이 둘 이상이고 순환 의존을 사람이 확인하기 어려워지면 Spring Modulith를 runtime
기본 의존성으로 강제하지 않고 test scope에서 먼저 검토한다.

```java
@Test
void moduleBoundaries() {
    ApplicationModules.of("com.example.app.module").verify();
}
```

Spring Modulith의 검증은 module 순환, 공개되지 않은 package 접근, 선언한 허용 의존성을
검사할 수 있고 module 단위 integration test도 제공한다. 관련 동작은
[공식 경계 검증 문서](https://docs.spring.io/spring-modulith/reference/verification.html)와
[module test 문서](https://docs.spring.io/spring-modulith/reference/testing.html)를 따른다.
이 설계의 얕은 단일 package module에서는 우선 순환과 허용 의존성을 검증하고, 구현
type 노출은 Java package visibility와 추가 ArchUnit rule로 보완한다. 실제 template에
의존성을 추가하기 전에는 현재 Spring Boot와의 version 조합을 별도 prototype으로
확인한다.

원래 portal이 frontend와 backend를 한 묶음으로 배포하던 역할은 되살리지 않는다.
backend module은 Maven artifact, frontend 재사용 기능은 framework별 source package 또는
npm package로 각각 versioning한다. 두 artifact의 호환 version은 문서나 BOM에서 맞추되
Java source 아래에 frontend 파일을 다시 섞지 않는다.

## 핵심 구현 형태

Root Struct는 로직과 상태를 갖지 않는 typed facade다. Java record를 사용하면 Python의
`struct.user` 탐색성을 boilerplate 없이 유지할 수 있다.

```java
@Component
public record Struct(
        UserStruct user,
        PostStruct post,
        ChatStruct chat,
        DashboardStruct dashboard) {
}
```

Feature Struct가 기존 `service` 역할과 transaction boundary를 맡는다. 고정된 안전한
출력은 nested record로 두어 별도 DTO 파일을 만들지 않는다.

```java
@Component
@Transactional(readOnly = true)
public class UserStruct {
    private final UserRepository users;

    public UserStruct(UserRepository users) {
        this.users = users;
    }

    public record View(
            String id,
            String email,
            String name,
            String mobile,
            String role) {
    }

    public List<View> list(String text, String role) {
        return users.findAllByOrderByCreatedAtAsc().stream()
                .map(user -> new View(
                        user.getId(),
                        user.getEmail(),
                        user.getName(),
                        user.getMobile(),
                        user.getRole().value()))
                .toList();
    }
}
```

Controller는 resource endpoint와 HTTP validation만 가진다. endpoint 하나에서만 쓰는
request는 nested record로 둔다.

```java
@ApiController
public final class UserController {
    private final Struct struct;

    public UserController(Struct struct) {
        this.struct = struct;
    }

    record InviteRequest(@Email String email, String name, String role) {
    }

    @GetMapping("/members")
    List<UserStruct.View> members(
            @RequestParam(defaultValue = "") String text,
            @RequestParam(defaultValue = "") String role) {
        return struct.user().list(text, role);
    }
}
```

Spring singleton인 Struct나 Feature Struct의 field에는 request, session, current user를
저장하지 않는다. 현재 사용자 정보는 method parameter 또는 명시적인 request-scoped
`SessionContext`로 전달한다. request-scoped bean을 singleton에 주입할 때는 scoped proxy를
명시한다. Spring proxy가 transaction을 적용할 수 있도록 `@Transactional` Feature Struct는
`final` class나 `final` method로 선언하지 않는다.

## DTO, DAO, Service 생성 규칙

| 항목 | 기본 규칙 | 별도 type/file로 승격하는 조건 |
| --- | --- | --- |
| Request DTO | Controller nested record | 둘 이상의 Controller 또는 API version에서 공유 |
| Response DTO | Struct의 안전한 nested record | 독립 public contract로 versioning하거나 여러 feature가 공유 |
| Entity | API에 노출하지 않음 | JPA persistence를 사용할 때만 생성 |
| Repository | `model/{feature}`에서 Entity 옆에 배치 | Spring Data query가 실제로 필요할 때만 생성 |
| DAO | 만들지 않음 | 특수한 legacy adapter가 있고 Repository/JdbcClient로 표현할 수 없을 때만 검토 |
| Service | 만들지 않고 Struct가 use case 담당 | 외부 시스템 client, scheduler처럼 lifecycle과 책임이 다른 경우 구체적 이름 사용 |
| Mapper | 간단한 변환은 Struct 내부 method | 복수 aggregate·API version에서 반복되고 독립 테스트 가치가 있을 때만 생성 |

단순 CRUD에서 ORM 이점이 작다면 Feature Struct가 Spring `JdbcClient`를 직접 사용해
Entity와 Repository를 모두 생략할 수 있다. 관계, dirty checking, aggregate lifecycle이
필요하면 JPA Entity와 Spring Data Repository를 함께 사용한다. 한 기능 안에서 두 방식을
임의로 섞지 않는다. 1.0 sample 이전의 첫 단계에서는 구조 변경과 persistence 변경을
동시에 하지 않고 현재 JPA를 유지하는 편이 안전하다.

## Python WIZ, 1.0, 권장안 비교

| 관점 | Python 원본/0.2.x 철학 | 1.0 현재 구조 | 권장 구조 |
| --- | --- | --- | --- |
| build/runtime | WIZ가 source를 해석·변환하고 runtime dispatch | 표준 Maven/Spring Boot 직접 build | 1.0 방식 유지 |
| frontend/backend | 페이지·컴포넌트에 API가 함께 위치 | frontend와 resource API 분리 | 1.0 방식 유지 |
| HTTP 진입점 | 페이지별 `api.py`/`api.java` | `api/*Controller` | domain 단위 `controller/*Controller` |
| domain 진입점 | Root Struct와 sub-struct | Controller가 여러 Service를 직접 주입 | type-safe Root Struct 복원 |
| package 기준 | `model`, `controller`, `portal` | `api`, `api/model`, `service`, `domain`, `repository` | `controller`, `model/{feature}`, `config`, `security`, `exception`, `web`, 선택적 `module/{feature}` |
| persistence | Struct가 ORM/DB model을 직접 사용 | Service → Repository → Entity | Struct → co-located Repository/Entity 또는 JdbcClient |
| 데이터 계약 | 동적 dict/Map 중심 | 별도 API model record | nested record 기본, 공유할 때만 별도 contract |
| DI/lifecycle | 전역 `wiz`, 문자열 lookup, 동적 cache | Spring constructor injection | Spring constructor injection 유지 |
| transaction/validation | 관례·runtime 검사 비중이 큼 | annotation과 compile-time type | 1.0 방식 유지 |
| 재사용 package | `portal/{name}` 동적 조합 | 별도 개념 없음 | 명시적 계약을 가진 `module/{feature}`, 필요 시 Spring Boot auto-configuration JAR |
| 기능 탐색 비용 | 짧지만 동작이 암시적 | 명시적이지만 여러 기술 폴더를 왕복 | 짧고 명시적인 의존 방향 |

## 1.0 class 이동안

| 1.0 현재 | 권장 위치/형태 | 판단 |
| --- | --- | --- |
| `api/AuthController`, `MemberController`, `ProfileController` | `controller/UserController` | 같은 사용자 domain의 endpoint를 한 곳에서 탐색. 독립 version/security 경계가 생기면 다시 분리 |
| `api/PostController`, `ChatController`, `DashboardController` | `controller` 또는 실제 재사용 시 `module/{feature}` | 페이지가 아닌 domain 기준 유지 |
| `api/model/*Models` 7개 | Controller request record + Struct result record | 기본 sample의 별도 DTO package 제거 |
| `service/UserService` | `model/user/UserStruct` | 사용자 use case와 transaction의 단일 이름 |
| `service/PostService`, `ChatService`, `DashboardService` | `model/{feature}/*Struct` | Service와 Struct 중복 방지 |
| `service/SessionAuthService` | `security/SessionContext` | request/session 경계를 domain state와 분리 |
| `domain/*`, `repository/*` | 관련 `model/{feature}` 또는 `module/{feature}` | 객체별 package에서 데이터와 행위를 함께 탐색 |
| `service/ChatEventHub` | `model/chat/ChatEventHub` 또는 `module/chat` | SSE lifecycle이 별도라 class 자체는 유지 |
| `api/ApiExceptionHandler`, `service/ApiException` | `exception` | 예외와 중앙 변환 정책을 명시적으로 배치 |
| `api/ApiController` | `web/ApiController` | 중앙 API prefix annotation을 HTTP 기반 기능으로 분류 |
| `api/ApiProperties`, `ApiWebConfiguration` | `config/ApiProperties`, `config/ApiConfiguration` | API prefix/versioning 설정을 Spring configuration으로 분류 |

실제 template refactor 결과 sample production 파일은 31개에서 23개로 8개 줄고,
공통 5개를 포함한 전체는 36개에서 28개로 줄었다. 더 중요한 변화는 한 domain
수정 시 방문하는 기술 package가 5개에서 `controller`와 해당 `model/{feature}` 두 곳
중심으로 줄어드는 점이다.

## 분리와 병합의 판단 기준

- 새 기능은 `XController`와 `model/x/XStruct` 두 파일로 시작한다.
- endpoint 전용 record 때문에 파일을 만들지 않는다.
- Entity를 API response로 쓰기 위해 DTO를 없애지는 않는다. 비밀번호 hash 같은 내부
  field가 있는 Entity는 반드시 안전한 projection으로 변환한다.
- Controller가 독립된 URI version, 보안 정책, 배포 ownership을 갖거나 review가 어려울
  정도로 커질 때 분리한다. 단순히 endpoint가 하나 늘었다는 이유로 package를 추가하지
  않는다.
- `model` root에는 `Struct.java`만 두고 모든 업무 class는 `model/{feature}`에 둔다.
- `model/{feature}/entity`, `model/{feature}/repository` 같은 세 번째 계층은 만들지
  않는다. 그룹이 너무 크면 더 깊게 나누지 않고 같은 깊이의 업무 그룹으로 분리한다.
- `support`, `common`, `util`, `manager`, `helper` 같은 포괄 package는 만들지 않는다.
  기반 기능은 `config`, `security`, `exception`, `web` 중 실제 책임에 둔다.

## 적용 순서 (수행 완료)

1. 현재 `SampleApiIntegrationTest`, API prefix/version test를 구조 변경 전의 HTTP
   characterization test로 고정한다.
2. `model/Struct`를 추가하고 기존 Service를 감싸 endpoint/JSON/상태 코드를 그대로
   유지한다.
3. Service를 Feature Struct로 이름 변경하고 Entity/Repository를 같은
   `model/{feature}` package로 이동한다. 이 단계에서는 JPA schema와 query를 바꾸지
   않는다.
4. API request/response record를 Controller와 Struct에 병합하고, OpenAPI schema와
   frontend client contract가 동일한지 확인한다.
5. 실제 재사용 기준을 만족하는 기능만 `module/{feature}`로 이동한다. sample이거나
   파일이 많다는 이유만으로 module을 만들지 않는다.
6. 공통·sample manifest, backend AI 지침, 생성 테스트를 함께 갱신한다.
7. 다섯 frontend template 생성, Maven test/package, frontend build, login/CRUD/SSE
   smoke를 통과한 뒤 다음 minor release의 새 프로젝트 기본 구조로 반영한다.

기존 1.0 생성 프로젝트는 generator runtime에 의존하지 않으므로 강제 이동하지 않는다.
새 구조는 새로 생성되는 프로젝트에 적용하고, 기존 프로젝트에는 endpoint를 보존하는
선택적 migration guide만 제공한다.

## 수용 기준

- 표준 `src/main/java`와 Maven/Spring Boot 직접 build가 유지된다.
- 페이지/컴포넌트별 backend API와 build-time source rewrite가 다시 생기지 않는다.
- fresh sample에 `dto`, `dao`, `service`, 전역 `domain`, 전역 `repository` package가 없다.
- `model` 아래는 `model/{feature}`까지만 사용하고 더 깊은 package가 없다.
- `support`, `common`, `util` 같은 포괄적인 기반 package가 없다.
- backend `portal` package가 없고, 재사용 기능만 `module/{feature}`에 존재한다.
- module은 다른 module의 Entity나 Repository를 직접 참조하지 않는다.
- Controller가 Entity를 JSON으로 직접 반환하지 않는다.
- 모든 domain 의존은 compile-time type과 constructor injection으로 연결된다.
- 기존 API path, status, JSON field, session 권한, transaction, SSE cursor 계약이
  integration test에서 동일하다.
- 다섯 frontend template의 생성·build·bundle이 모두 통과한다.

## 1.1.0 적용 결과

- 공통 manifest는 `Application`, `config/ApiConfiguration`, `config/ApiProperties`,
  `web/ApiController`, `web/RuntimeConfigurationController` 5개 production type을 생성한다.
- fresh sample manifest는 Controller 4개, Root Struct 1개, feature model 13개,
  named infrastructure 5개로 production type 23개를 생성한다.
- generator test 81개와 helper container의 `gofmt`, `go vet`, Go test가 통과했다.
- HTML, JSP, Angular WIZ, Angular, React를 각각 fresh create하여 Spring 통합 테스트,
  frontend production build, bundle 생성, bundle SHA-256 검증을 완료했다.
- 기존 endpoint·JSON·session·transaction·SSE characterization test가 동일한 계약으로 통과했다.

## 리스크와 대응

| 리스크 | 대응 |
| --- | --- |
| Root Struct가 God object가 됨 | accessor와 bean 조합만 허용하고 business method/state를 두지 않음 |
| `model/{feature}`가 커짐 | 같은 깊이의 독립 업무 그룹으로 분리하고 세 번째 계층은 만들지 않음 |
| 기반 package가 다시 모호해짐 | `config`, `security`, `exception`, `web`의 책임과 허용 class를 고정 |
| nested record가 API와 domain을 과도하게 결합 | 공유·versioning 필요 시에만 별도 contract로 승격 |
| Controller 병합으로 class가 커짐 | 독립 URI version·보안 정책·소유권을 기준으로 다시 분리 |
| JPA 파일이 여전히 필요함 | JPA를 쓰는 기능에서만 Entity/Repository를 허용하고 DAO를 중복 추가하지 않음 |
| module이 이름만 바꾼 portal이 됨 | public contract·독립 test·재사용 조건을 충족할 때만 승격 |
| 얕은 module package에서 구현 type이 노출됨 | package-private 우선, public Entity/Repository 접근은 ArchUnit rule로 차단 |
| 외부 module이 application API prefix에 결합됨 | core JAR에서 Controller를 분리하고 application adapter 또는 별도 web auto-configuration 사용 |
| module event가 과도하게 복잡해짐 | 즉시 결과는 interface, 부가 효과만 event로 나누고 durable 처리 필요성을 별도 판단 |
| Spring Modulith 의존성이 기본 복잡도를 늘림 | baseline에는 강제하지 않고 module 수가 늘 때 test scope prototype부터 적용 |
| package 이동 중 API 회귀 | persistence 변경과 분리하고 기존 integration/OpenAPI/frontend contract로 검증 |

이 문서는 구조 결정과 `1.1.0` template 적용·검증 결과를 함께 기록한다.
