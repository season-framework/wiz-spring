# Angular WIZ frontend rules

This project keeps the human- and AI-friendly WIZ frontend layout while producing a normal Angular 22 application.

- Edit WIZ components in `src/app/<app-id>/app.json` and `view.ts`, `view.pug` or `view.html`, and `view.scss`.
- Legacy Angular WIZ reusable frontend sources live in `src/portal/<name>/app`; their `assets` and `libs` are staged automatically. This frontend-only source namespace is not a Java backend `portal` or `module` package.
- Keep the Angular shell in `src/angular`. Root application bootstrap, global styles, and the small `wiz.ts` browser client belong there.
- `npm run wizbuild` compiles Pug, generates Angular components and routes, runs the local Angular CLI, and writes static output to `target/generated-resources/frontend`.
- `npm run wizwatch` debounces WIZ source changes and keeps Angular's incremental build running. Use `npm run dev` when Spring should run alongside it.
- Never edit `target/wiz-angular` or `target/generated-resources/frontend`; both are disposable generated output.
- Do not add `.wiz`, `api.java`, `socket.java`, or a private WIZ NPM package. The committed `scripts/wizbuild.mjs` and `scripts/wiz/*.mjs` files are the complete WIZ build implementation.
- `view.pug` takes precedence over `view.html` when both exist. Pug is compiled by the pinned `pug` development dependency.
- `app.json` uses `mode`, `id`, `viewuri`, and optional `layout`, `namespace`, `ng.selector`, and `ng.build.name`. Page routes are derived from `viewuri`.
- Call backend resources with `wiz.api`, for example `wiz.api.get('/dashboard')`. Use
  `wiz.api.eventSource('/chat/stream')` for SSE. Do not hard-code `/api`;
  `src/angular/wiz.ts` reads the prefix from `/app-config.json` at runtime.
- Keep build configuration in the root `package.json` and `angular.json`. The builder does not read nested manifests or rewrite Angular shell decorators.
- Backend endpoints are regular Spring controllers under `src/main/java`; frontend metadata never generates or dispatches Java code.

## Included sample

Fresh projects demonstrate the intended component boundaries with `page.access`,
`page.dashboard`, `page.posts`, `page.members`, `page.chat`, `page.mypage`, the reusable
`component.nav.sidebar`, and separate empty/sidebar layouts. The pages exercise session
login, typed CRUD calls, one-based post pagination, profile updates, and SSE. Reuse or
delete these source directories as the real product replaces the demo domain; never edit
their generated copies below `target/wiz-angular`.

Imported projects intentionally omit those sample directories. The chat sample opens SSE
with the last history message as its `after` cursor and sorts replayed events by ID; preserve
that handoff if the demo is adapted rather than replaced.

After changing vendored builder scripts, run `npm run test:wizbuild` and a clean `npm run wizbuild` before committing.
