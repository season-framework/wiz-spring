# Build and deployment rules

- The WIZ Spring `1.2.2` deployment baseline uses a full JDK 25 or newer, Maven
  Wrapper `3.9.15`, Node.js `^22.22.3 || ^24.15.0 || >=26.0.0`, and npm 8 or newer.
- Backend: `npm run backend:build`; it packages Spring without deleting the independently
  watched frontend output. Use direct `./mvnw clean package` only when the live development
  lifecycle is stopped or when a following frontend build will restore that output.
- Frontend: `npm run frontend:build`.
- Integrated build: `npm run build`.
- Live development: `npm run dev`. It performs initial builds and keeps backend and
  frontend output current. After a source edit, wait for the watcher to succeed; Java
  changes are applied by a trigger-controlled Spring DevTools restart inside the same process
  lifecycle. Failed compilation does not restart the last good application.
- `npm run backend:build` signals a running development application after backend success.
  `npm run build` sends that signal only after both backend and frontend builds succeed.
- Deployment directory: `npm run bundle`. Never manually edit `target/` or `bundle/`.
- A current bundle has exactly four top-level entries: `application.jar` (or the JSP
  `application.war`), `public/`, `.env`, and `docker-compose.yaml`. Do not add generated
  launchers, manifests, checksums, proxy images, proxy configuration, or build-tool files.
- Direct bundle execution needs only JDK 25+. From `bundle/`, load `.env` with a POSIX shell
  (`set -a; . ./.env; set +a`) and run `java -jar "$APP_ARTIFACT"`. Node, npm, Maven, and
  WIZ Spring must not be runtime dependencies.
- The Compose file runs only the prebuilt Spring application from a JRE image. It must not
  build an image or include Nginx/Apache HTTP Server services. Keep reverse proxies
  independently managed.
- `deploy/nginx/default.conf.example` and `deploy/apache2/wiz.conf.example` are editable
  host configuration examples and are intentionally outside the bundle. Preserve SPA/JSP
  routing, API boundary matching, WebSocket forwarding, and unbuffered SSE behavior when
  adapting them.
- `wiz-spring service install <name> --root <project>` installs live development by
  default and runs the same `npm run dev` lifecycle under systemd. Source edits are rebuilt
  and applied without reinstalling or manually restarting the service.
- Add `--production` only for a deployment bundle. It defaults to `<project>/bundle`;
  `--bundle <path>` both selects production mode and overrides that path. Current bundles
  use a root archive; the installer also accepts legacy 1.2.1 manifest/checksum bundles.
- Use `<project>/.env` as the single local configuration file. Every npm script and the
  default development service load it. Direct production execution and production services
  use `<bundle>/.env`. Both files are generated with usable defaults and must be edited
  directly; never introduce an example-file copy step. Keep defaults free of secrets and
  inject credentials through runtime configuration or an external service env file.
- Configuration precedence is service options (`--port`, `--profiles`), existing process
  environment, `.env`, then application defaults. Environment changes require a service
  restart; ordinary source changes do not.
- `service install` is only an administrative convenience. An installed service must execute
  the absolute npm/Java command and must never invoke the WIZ Spring CLI at runtime.
- Install systemd services with a dedicated non-root `--user`; service output is read with
  `wiz-spring service logs` from journald.
- The `prod` profile disables OpenAPI and Swagger UI by default. Enable them only with
  `SPRINGDOC_API_DOCS_ENABLED=true` and `SPRINGDOC_SWAGGER_UI_ENABLED=true` when policy
  permits public API documentation.
- Do not place credentials in the bundle.
