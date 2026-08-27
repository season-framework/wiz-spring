# HTML frontend rules

- Source files live in `frontend/` and are copied to `target/generated-resources/frontend`.
- Use browser-native modules unless the project deliberately adopts a framework.
- Read `/app-config.json` at runtime and prepend its `apiPrefix` to business API paths.
- Run `npm run frontend:watch` while editing static files.
- `frontend/app.js` owns bootstrap only. API transport, routing, state, and rendering helpers live under `frontend/lib`; feature screens live under `frontend/views`.
- Navigation is hash-based so a generated project works without a server-side SPA fallback. Add screens through `lib/router.js` and the route table in `app.js` together.
- Keep authenticated requests in `lib/api.js`. It sends same-origin credentials, translates the backend error envelope, and creates prefix-aware SSE connections.
- The sample backend uses one-based post pages. Preserve that contract in pagination controls.
- Keep frontend Node tests under `src/test/frontend`; anything below `frontend/` is copied into the production static output.
- Fresh projects include the modular sample screens; `--uri` and `--path` imports do not.
- Start chat SSE with the last history message as the `after` cursor and keep replayed messages ordered by ID.
