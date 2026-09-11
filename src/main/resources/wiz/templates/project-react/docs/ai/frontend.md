# React frontend rules

- WIZ Spring `1.2.2` pins React and React DOM `19.2.8`, Vite `8.2.2`, and
  `@vitejs/plugin-react` `6.1.1`. `package.json` and `package-lock.json` are authoritative.
- This is a normal React and Vite application; it does not use WIZ component generation.
- Source lives under `frontend/` and production output is `target/generated-resources/frontend`.
- Read `/app-config.json` at runtime. Never hardcode the configured API prefix in components.
- `npm run dev` uses Vite's build watcher and writes every successful rebuild to
  `target/generated-resources/frontend`, which Spring serves on its own port. Do not replace
  it with an in-memory dev server or edit the generated output.
- Use `npm run frontend:serve` only when you explicitly want Vite's separate development
  server with HMR. Its proxy follows `APP_API_PREFIX`; keep it aligned with Spring.
- Keep server state and view state separate, and keep API calls in small typed modules as the application grows.
- `frontend/src/router.js` intentionally provides dependency-free hash routing. Authenticated
  pages render inside `layout/AppShell.jsx`; add a routing package only when the product needs it.
- Keep runtime prefix, cookie credentials, error parsing, and URL construction in
  `frontend/src/api/client.js` rather than duplicating fetch calls in pages.
- The sample pages demonstrate dashboard data, member and post CRUD, profile/password forms,
  and the named `chat.message` SSE event. Preserve these API contracts when replacing the demo.
- Fresh projects include those sample pages; `--uri` and `--path` imports do not. The chat
  page passes the last history ID as the SSE `after` cursor so replay closes the connection gap.
- After dependency or build-configuration changes, run `npm ci` and
  `npm run frontend:build`; update this guide and the project README with the new baseline.
