# Build and deployment rules

- The WIZ Spring `1.2.1` deployment baseline uses a full JDK 25 or newer, Maven
  Wrapper `3.9.15`, Node.js
  `^22.22.3 || ^24.15.0 || >=26.0.0`, and npm 8 or newer.
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
  `npm run build` sends that signal only after both its backend and frontend builds succeed.
- Deployment directory: `npm run bundle`.
- Never manually edit `target/` or `bundle/`.
- The bundle manifest selects `app/application.jar` or `app/application.war`. The bundle's
  `./run.sh` launches it with only JDK 25+ and a POSIX shell; Node, npm, Maven, and WIZ Spring
  must not be runtime dependencies.
- Verify a copied bundle with `(cd bundle && sha256sum -c SHA256SUMS)` before deployment; service installation also rejects checksum mismatches.
- `wiz-spring service install <name> --root <project>` installs live development by
  default and runs the same `npm run dev` lifecycle under systemd. Source edits are rebuilt
  and applied without reinstalling or manually restarting the service.
- Add `--production` only for an immutable deployment bundle. It defaults to
  `<project>/bundle`; `--bundle <path>` both selects production mode and overrides that path.
- Use `<project>/.env` as the single local configuration file. Every npm script and the
  default development service load it. Production direct execution and production services
  load `<bundle>/.env`. Both files are generated with usable defaults and must be edited
  directly; never introduce an example-file copy step. Keep the project defaults free of
  secrets and inject credentials through exported variables or an external service env file.
- Configuration precedence is service options (`--port`, `--profiles`), existing process
  environment, `.env`, then application defaults. Environment changes require a service
  restart; ordinary source changes do not.
- `service install` is only an administrative convenience. Inspect the generated unit or
  launcher when changing it: an installed service must execute the absolute npm/Java command
  and must never invoke the WIZ Spring CLI at runtime.
- Install systemd services with a dedicated non-root `--user`; service output is read with `wiz-spring service logs` from journald.
- `docker compose --profile nginx up -d` and `--profile apache2 up -d` are alternative proxy deployments.
- The generated backend image runs as UID/GID 10001 and preserves the `.jar` or `.war` artifact extension.
- The `prod` profile disables OpenAPI and Swagger UI by default. Enable them only with
  `SPRINGDOC_API_DOCS_ENABLED=true` and `SPRINGDOC_SWAGGER_UI_ENABLED=true` when the
  deployment policy permits public API documentation.
- Do not place credentials in the bundle. Supply them with environment variables or external Spring configuration.
