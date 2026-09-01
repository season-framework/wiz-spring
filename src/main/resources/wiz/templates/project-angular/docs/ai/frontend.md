# Angular frontend rules

- WIZ Spring `1.1.1` pins Angular runtime `22.1.4`, Angular CLI/build `22.1.6`, and
  TypeScript `6.0.3`. `package.json` and `package-lock.json` are authoritative.
- This is a normal Angular CLI workspace; it does not use WIZ component generation.
- Application source lives under `frontend/src` and production output is `target/generated-resources/frontend`.
- Keep API paths relative and resolve the prefix through `/app-config.json`.
- The development proxy follows `APP_API_PREFIX`; do not add a hard-coded API proxy entry.
- Use standalone Angular components and Angular signals for local UI state where appropriate.
- Routes are declared in `frontend/src/app/app.routes.ts`; authenticated pages render inside
  `layout/app-shell.component.ts`, while the login page uses its own full-screen layout.
- Keep the session cookie flow in `session.service.ts` and API prefix/error handling in
  `api.service.ts`. All requests must include credentials.
- The sample pages under `frontend/src/app/pages` demonstrate dashboard data, member and post
  CRUD, profile/password forms, and the named `chat.message` SSE event. Preserve these API
  contracts when replacing the sample UI with product features.
- Fresh projects include those sample pages; `--uri` and `--path` imports do not. The chat
  page passes the last history ID as the SSE `after` cursor so replay closes the connection gap.
- After dependency or build-configuration changes, run `npm ci` and
  `npm run frontend:build`; update this guide and the project README with the new baseline.
