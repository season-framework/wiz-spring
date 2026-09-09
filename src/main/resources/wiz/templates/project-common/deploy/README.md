# Deployment bundle

The backend artifact and frontend files were built from the same source revision.
Verify the bundle before deploying it:

```bash
sha256sum -c SHA256SUMS
```

Run the complete application directly from this directory:

```bash
./run.sh
```

This bundle already contains `.env` with production defaults. Edit it directly when needed.

This copied directory needs only JDK 25+ and a POSIX shell. It does not need Node.js,
npm, Maven, the generator JAR, or a `wiz-spring` executable. `run.sh` loads this directory's
`.env`, keeps any already exported environment value as the higher-priority value, selects
the `prod,bundle` profiles by default, and then runs `app/application.__WIZ_ARTIFACT_TYPE__`.
Spring command-line options can be appended, for example `./run.sh --server.port=9090`.
The production profile disables API docs and Swagger UI by default. Set
`SPRINGDOC_API_DOCS_ENABLED=true` and `SPRINGDOC_SWAGGER_UI_ENABLED=true` only when
those endpoints should be exposed.

For a reverse proxy, edit `.env` when needed, then choose exactly one profile:

```bash
docker compose --profile nginx up -d
docker compose --profile apache2 up -d
```

`.env` and `data/` are the only runtime-mutable locations intentionally allowed beside the
checksum-protected bundle files. Keep any additional Spring configuration outside the bundle and set
`SPRING_CONFIG_ADDITIONAL_LOCATION` in `.env`.

Install this immutable output from the generated project root with:

```bash
wiz-spring service install <name> --production --root . --user <service-user>
```

From this bundle directory, `wiz-spring service install <name> --bundle . --user
<service-user>` is equivalent. `--bundle <path>` selects production mode and overrides the
default bundle path. The CLI is only a one-time installer/administrator: the installed
systemd launcher executes Java directly and has no runtime dependency on WIZ Spring.
Omit `--production` only when intentionally installing the editable project as a
live-development service; that mode runs `npm run dev` and does not execute this bundle.

The production service reads `<bundle>/.env` by default. Pass `--env-file <path>` to choose
another file. `--port` and `--profiles` override values from that file. Restart the service
after changing environment configuration; rebuilding is unnecessary for configuration-only
changes.

The backend container runs as the non-root UID/GID `10001`. Keep bundled files
world-readable or owned by that identity if you replace them at deployment time.
The Compose file mounts the `backend-data` named volume at `/app/data`, preserving the
sample H2 database across container replacement. Replace that volume or configure an
external datasource before treating the sample as a production data store.

Both proxy profiles disable buffering and extend the read timeout on proxied API
responses so `/api/chat/stream` can deliver SSE events immediately.

Set secrets through environment variables or that external Spring configuration file.
TLS termination and certificate provisioning are intentionally not included.
