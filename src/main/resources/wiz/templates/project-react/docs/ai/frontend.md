# React frontend rules

- This is a normal React and Vite application; it does not use WIZ component generation.
- Source lives under `frontend/` and production output is `target/generated-resources/frontend`.
- Read `/app-config.json` at runtime. Never hardcode the configured API prefix in components.
- The Vite development proxy follows `APP_API_PREFIX`; keep it aligned with the Spring process.
- Keep server state and view state separate, and keep API calls in small typed modules as the application grows.
- `frontend/src/router.js` intentionally provides dependency-free hash routing. Authenticated
  pages render inside `layout/AppShell.jsx`; add a routing package only when the product needs it.
- Keep runtime prefix, cookie credentials, error parsing, and URL construction in
  `frontend/src/api/client.js` rather than duplicating fetch calls in pages.
- The sample pages demonstrate dashboard data, member and post CRUD, profile/password forms,
  and the named `chat.message` SSE event. Preserve these API contracts when replacing the demo.
- Fresh projects include those sample pages; `--uri` and `--path` imports do not. The chat
  page passes the last history ID as the SSE `after` cursor so replay closes the connection gap.
