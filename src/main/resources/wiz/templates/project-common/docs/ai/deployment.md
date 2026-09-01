# Build and deployment rules

- The WIZ Spring `1.1.1` deployment baseline uses a full JDK 25 or newer, Maven
  Wrapper `3.9.15`, Node.js
  `^22.22.3 || ^24.15.0`, and npm 10 or newer.
- Backend: `npm run backend:build` or `./mvnw clean package`.
- Frontend: `npm run frontend:build`.
- Integrated build: `npm run build`.
- Deployment directory: `npm run bundle`.
- Never manually edit `target/` or `bundle/`.
- The bundle manifest selects `app/application.jar` or `app/application.war` and is consumed by `wiz-spring service install`.
- Verify a copied bundle with `(cd bundle && sha256sum -c SHA256SUMS)` before deployment; service installation also rejects checksum mismatches.
- Install systemd services with a dedicated non-root `--user`; service output is read with `wiz-spring service logs` from journald.
- `docker compose --profile nginx up -d` and `--profile apache2 up -d` are alternative proxy deployments.
- The generated backend image runs as UID/GID 10001 and preserves the `.jar` or `.war` artifact extension.
- The `prod` profile disables OpenAPI and Swagger UI by default. Enable them only with
  `SPRINGDOC_API_DOCS_ENABLED=true` and `SPRINGDOC_SWAGGER_UI_ENABLED=true` when the
  deployment policy permits public API documentation.
- Do not place credentials in the bundle. Supply them with environment variables or external Spring configuration.
