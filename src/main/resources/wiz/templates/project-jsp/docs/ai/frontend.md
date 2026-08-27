# JSP frontend rules

- JSP views live under `src/main/webapp/WEB-INF/jsp` and are rendered by Spring MVC controllers.
- The deployment artifact is an executable WAR, not a JAR.
- Browser assets live under `src/main/webapp/assets`; `npm run frontend:build` stages a public copy for reverse proxies.
- Business JSON APIs still use `@ApiController` and the central API prefix.
- Do not move JSP files into `src/main/resources/static`.
- `HomeController` owns browser routes and redirects anonymous sessions to `/access`. Keep business operations in the JSON API rather than duplicating them in the MVC controller.
- Reuse the JSP fragments under `WEB-INF/jsp/fragments` for the document head, responsive sidebar, and shell. Feature JSP files should contain semantic page structure and stable DOM hooks.
- Browser transport belongs in `assets/js/api.js`; it loads `/app-config.json`, includes the servlet context path, sends the session cookie, and creates prefix-aware SSE connections.
- Keep one feature entry module under `assets/js/pages` per JSP view. The chat page listens for the named `chat.message` event.
- Post pagination is one-based. Preserve the backend `page >= 1` contract in browser controls.
- `npm run frontend:build` stages only `src/main/webapp/assets`. JSP files remain inside the executable WAR and browser tests under `src/test/frontend` must not be copied to the public bundle.
- Fresh projects include `HomeController`, the feature JSPs, and their browser modules; `--uri` and `--path` imports do not.
- Start chat SSE with the last history message as the `after` cursor and keep replayed messages ordered by ID.
