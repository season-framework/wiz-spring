# Spring backend rules

The backend is ordinary Spring Boot source. `wiz-spring` does not transform or compile it.

- Put controllers, services, repositories, entities, configuration, and tests in their standard Maven directories.
- Use `@ApiController("/dashboard")` for business APIs. The annotation is project source and applies `app.api.prefix` centrally.
- Do not use `${app.api.prefix}` inside controller annotations.
- Keep `/app-config.json`, Actuator, Swagger UI, and OpenAPI outside the business API prefix.
- For one active URI version, set `app.api.prefix=/api/v2`.
- For simultaneous path versions, set versioning mode to `path`, keep prefix `/api`, set a required `default-version`, and declare controller mapping versions.
- Prefer normal springdoc annotations and typed request/response records. Do not synthesize endpoints from frontend metadata.

## Fresh-project sample domain

Freshly generated projects include a working reference application, not a mock endpoint.
Projects created with `--uri` or `--path` intentionally omit the sample classes and
tests below; in an imported project, apply only the general Spring/API rules above.

- `domain/` contains JPA users, posts, and chat messages.
- `repository/` contains Spring Data JPA repositories.
- `service/` owns authentication, authorization, transactions, search, and SSE fan-out.
- `api/model/` contains typed JSON request and response records.
- `config/SampleDataInitializer` idempotently creates five demo users and three posts.
- H2 persists to `data/sample.mv.db` by default. Override `APP_DATASOURCE_URL`,
  `APP_DATASOURCE_USERNAME`, and `APP_DATASOURCE_PASSWORD` for another environment.

Use the existing Servlet session helpers for protected sample endpoints. Never return
`UserEntity` directly because it contains a BCrypt password hash. Member mutations are
admin-only, and post mutation is limited to the author or an admin. Chat uses standard
Spring MVC SSE at `/chat/stream`. It publishes only after the database transaction commits,
assigns each event its message ID, and replays rows after the `after`/`Last-Event-ID` cursor;
retain that cursor contract and same-origin credentials on browser EventSource connections.

The primary integration test is `SampleApiIntegrationTest`. When changing a request or
response shape, update that test, the frontend template using it, and Swagger-visible
types together.
