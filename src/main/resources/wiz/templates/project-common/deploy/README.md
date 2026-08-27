# Deployment bundle

The backend artifact and frontend files were built from the same source revision.
Verify the bundle before deploying it:

```bash
sha256sum -c SHA256SUMS
```

Run the backend directly from this directory:

Run `java -jar app/application.__WIZ_ARTIFACT_TYPE__ --spring.profiles.active=prod,bundle`.

For a reverse proxy, copy `.env.example` to `.env`, then choose exactly one profile:

```bash
docker compose --profile nginx up -d
docker compose --profile apache2 up -d
```

`.env` is the only runtime-local file intentionally allowed beside the checksum-protected
bundle files, so the same bundle remains valid for `wiz-spring service install`. Keep any
additional Spring configuration outside the bundle and reference it with
`SPRING_CONFIG_ADDITIONAL_LOCATION` (for systemd, use a unit drop-in).

The backend container runs as the non-root UID/GID `10001`. Keep bundled files
world-readable or owned by that identity if you replace them at deployment time.
The Compose file mounts the `backend-data` named volume at `/app/data`, preserving the
sample H2 database across container replacement. Replace that volume or configure an
external datasource before treating the sample as a production data store.

Both proxy profiles disable buffering and extend the read timeout on proxied API
responses so `/api/chat/stream` can deliver SSE events immediately.

Set secrets through environment variables or that external Spring configuration file.
TLS termination and certificate provisioning are intentionally not included.
