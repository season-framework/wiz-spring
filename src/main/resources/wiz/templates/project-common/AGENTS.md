# Project instructions

This is a standard Spring Boot backend with a `__WIZ_FRONTEND__` frontend.

- Backend code lives only under `src/main/java`; do not add Java handlers to frontend folders.
- Keep the application flow `Controller -> model.Struct -> model/<feature>/<Feature>Struct`.
- Put endpoint-only request records inside their Controller and safe response records inside the Feature Struct. Never expose JPA entities as JSON.
- Keep each business aggregate in one `model/<feature>` package with its Struct, Entity, Repository, and related types. Do not add a deeper `entity`, `repository`, or `dto` package.
- Do not create global `service`, `domain`, `repository`, `dto`, `dao`, `support`, `common`, `util`, `manager`, or `helper` packages. A Feature Struct owns use cases and transactions.
- Use `config`, `security`, `exception`, and `web` only for the responsibility named by the package. Do not merge them into a catch-all package.
- Business API controllers use `web.ApiController("/resource")`. Never repeat the configured `/api` prefix in a controller.
- `app.api.prefix` in `src/main/resources/application.yml` is the single API-prefix source of truth.
- Use `module/<feature>` only for a genuinely reusable backend boundary with a small public contract and independent tests. Never create a backend `portal` package or mix frontend files into a Java module.
- Keep `model.Struct` stateless: it only exposes typed feature accessors. Request/session state belongs in the request-scoped `security.SessionContext`.
- Generated frontend output belongs in `target/generated-resources/frontend`; do not edit it.
- `bundle/` and `target/` are generated outputs and must not be edited or committed.
- Run `npm run frontend:build` for the frontend, `npm run backend:build` for Spring, `npm run build` for both, and `npm run bundle` for deployment output.
- Do not add a `.wiz` directory or a private WIZ build package. Build support is committed under `scripts/`.

Read `docs/ai/backend-spring.md`, `docs/ai/frontend.md`, and `docs/ai/deployment.md` before structural changes.
