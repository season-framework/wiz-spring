# Reverse proxy examples

`npm run bundle` creates a deliberately small deployment directory containing only the
root `application.jar` (`application.war` for JSP), frontend files, environment defaults,
and the Compose sample:

```text
bundle/
├── application.__WIZ_ARTIFACT_TYPE__
├── public/
├── .env
└── docker-compose.yaml
```

The bundle does not include or start Nginx or Apache HTTP Server. Run the Spring application
directly or with the included single-service Compose file, then configure an independently
managed reverse proxy when one is needed.

## Run the application

From the bundle directory, a POSIX shell can load the generated environment file and launch Java:

```bash
set -a
. ./.env
set +a
java -jar "$APP_ARTIFACT"
```

Alternatively:

```bash
docker compose up -d
```

The Compose service publishes `SERVER_PORT` (8080 by default), mounts `public/` read-only, and
keeps application data in a named volume. It requires neither a source tree nor a local image
build.

## Nginx

`nginx/default.conf.example` assumes:

- the application listens on `127.0.0.1:8080`;
- the frontend files are installed at `/srv/wiz/public`;
- the application API prefix is `/api`.

Copy it into the host's Nginx configuration, change those values and `server_name` for the
deployment, validate with `nginx -t`, and reload Nginx. The example includes SPA fallback,
WebSocket forwarding, and unbuffered long-lived API responses for SSE.

## Apache HTTP Server

`apache2/wiz.conf.example` uses the same defaults. Enable the `proxy`, `proxy_http`,
`proxy_wstunnel`, `rewrite`, and `headers` modules, copy the example into the host's virtual-host
configuration, adjust it, validate it, and reload Apache HTTP Server.

The JSP template receives proxy examples tailored to its executable WAR: `/assets/` is served
from `/srv/wiz/public`, while other requests are forwarded to Spring so JSP rendering stays in
the application.

TLS certificates and secret provisioning are intentionally site-specific. Keep secrets outside
the generated project and bundle, and expose API documentation only when the deployment requires
it.

## systemd installation

After creating the bundle, WIZ Spring can install it as a systemd service:

```bash
wiz-spring service install <name> --production --root . --user <service-user>
```

From inside the bundle, `--bundle .` is equivalent. The installed service executes Java directly,
loads the bundle `.env`, and does not depend on WIZ Spring, Node.js, npm, or Maven at runtime.
