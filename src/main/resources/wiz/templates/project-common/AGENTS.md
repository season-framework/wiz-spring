# Project instructions

This is a standard Spring Boot backend with a `__WIZ_FRONTEND__` frontend.

- Backend code lives only under `src/main/java`; do not add Java handlers to frontend folders.
- Business API controllers use `@ApiController("/resource")`. Never repeat the configured `/api` prefix in a controller.
- `app.api.prefix` in `src/main/resources/application.yml` is the single API-prefix source of truth.
- Generated frontend output belongs in `target/generated-resources/frontend`; do not edit it.
- `bundle/` and `target/` are generated outputs and must not be edited or committed.
- Run `npm run frontend:build` for the frontend, `npm run backend:build` for Spring, `npm run build` for both, and `npm run bundle` for deployment output.
- Do not add a `.wiz` directory or a private WIZ build package. Build support is committed under `scripts/`.

Read `docs/ai/backend-spring.md`, `docs/ai/frontend.md`, and `docs/ai/deployment.md` before structural changes.
