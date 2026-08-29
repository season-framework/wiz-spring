# Spring backend rules

The backend is ordinary Spring Boot source. `wiz-spring` does not transform or compile it.

- Keep Java and tests in their standard Maven source directories.
- Organize application code by this dependency flow:

  ```text
  HTTP -> controller -> model.Struct -> model/<feature>/<Feature>Struct -> Repository or JdbcClient
  ```

- `model/Struct` is a stateless typed facade. It may only expose feature accessors; business methods and request state do not belong there.
- Start a feature with `controller/XController` and `model/x/XStruct`. If persistence is needed, keep `XEntity`, `XRepository`, enums, and feature collaborators beside the Struct in that same `model/x` package.
- `model/<feature>` is the maximum model depth. Split a large feature into another peer feature instead of adding `model/x/entity`, `model/x/repository`, or `model/x/dto`.
- A Feature Struct owns use cases and `@Transactional` boundaries. Do not add a parallel `XService` for the same work, and do not make a transactional Struct class or method `final`.
- Endpoint-only inputs are public nested records in the Controller. Stable, safe outputs are public nested records in the Feature Struct. Promote a record to its own contract file only when multiple controllers, versions, or modules genuinely share it.
- Never return a JPA entity from a Controller. In particular, `UserEntity` contains a password hash and must always be projected to `UserStruct.View`.
- Use `web.ApiController("/dashboard")` for business APIs. The annotation is project source and applies `app.api.prefix` centrally.
- Do not use `${app.api.prefix}` inside controller annotations.
- Keep `/app-config.json`, Actuator, Swagger UI, and OpenAPI outside the business API prefix.
- For one active URI version, set `app.api.prefix=/api/v2`.
- For simultaneous path versions, set versioning mode to `path`, keep prefix `/api`, set a required `default-version`, and declare controller mapping versions.
- Prefer normal springdoc annotations and typed request/response records. Do not synthesize endpoints from frontend metadata.

## Named infrastructure boundaries

- `config`: Spring configuration and typed configuration properties only.
- `security`: authentication/authorization context and policy. The sample `SessionContext` is explicitly request-scoped and is injected into singleton Structs through a scoped proxy.
- `exception`: application exceptions and their HTTP translation.
- `web`: transport infrastructure such as `ApiController` and runtime frontend configuration.
- Do not create `support`, `common`, `util`, `manager`, or `helper` catch-all packages. Use a responsibility-revealing type and package, or keep a small helper private inside its owner.

## Reusable modules

Project-specific features stay in `model/<feature>`. Use `module/<feature>` only when the feature has a concrete second consumer or independent version/deployment need, a small public operations/event contract, and a standalone Spring context test. Move the whole feature; never duplicate it in both `model` and `module`.

Keep a project-local module in one package without `controller`, `service`, or `repository` subpackages. Connect modules with constructor-injected interfaces for synchronous work and Spring application events for post-transaction side effects. If a reusable module becomes an external JAR, use Spring Boot auto-configuration and typed `@ConfigurationProperties`; keep application-specific `web.ApiController` adapters in the application.

Do not create a backend `portal` package. Frontend reusable code remains in its framework source/package or npm artifact and is versioned separately from a backend module.

## Fresh-project sample domain

Freshly generated projects include a working reference application, not a mock endpoint.
Projects created with `--uri` or `--path` intentionally omit the sample classes and
tests below; in an imported project, apply only the general Spring/API rules above.

- `controller/` contains four resource controllers. Auth, member, and profile endpoints are grouped in `UserController` because they share one aggregate.
- `model/Struct` composes `UserStruct`, `PostStruct`, `ChatStruct`, and `DashboardStruct` through constructor injection.
- `model/user`, `model/post`, `model/chat`, and `model/dashboard` co-locate each feature's behavior and persistence types at one depth.
- `security/SessionContext` owns current-request session state; singleton Structs never store current-user state in fields.
- `exception/`, `config/`, and `web/` contain only their named infrastructure responsibilities.
- `config/SampleDataInitializer` idempotently creates five demo users and three posts.
- H2 persists to `data/sample.mv.db` by default. Override `APP_DATASOURCE_URL`,
  `APP_DATASOURCE_USERNAME`, and `APP_DATASOURCE_PASSWORD` for another environment.

Use `SessionContext` for protected sample endpoints. Never return
`UserEntity` directly because it contains a BCrypt password hash. Member mutations are
admin-only, and post mutation is limited to the author or an admin. Chat uses standard
Spring MVC SSE at `/chat/stream`. It publishes only after the database transaction commits,
assigns each event its message ID, and replays rows after the `after`/`Last-Event-ID` cursor;
retain that cursor contract and same-origin credentials on browser EventSource connections.

The primary integration test is `SampleApiIntegrationTest`. When changing a request or
response shape, update that test, the frontend template using it, and Swagger-visible
types together.

## Migrating a generated 1.0 backend

Migration is optional; generated projects do not depend on the generator at runtime. Preserve HTTP characterization tests first, then apply these moves without changing persistence or endpoint contracts:

1. Add `model/Struct`, inject it into Controllers, and keep it free of logic/state.
2. Rename each `*Service` to its feature `*Struct`; move its Entity and Repository into the same `model/<feature>` package.
3. Move Controller-only request records into the Controller and safe response records into the Struct.
4. Move session handling to `security/SessionContext`, API configuration to `config`, exceptions to `exception`, and transport infrastructure to `web`.
5. Remove the old empty `api/model`, `service`, `domain`, and `repository` packages only after integration and OpenAPI checks pass.
