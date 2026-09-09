[English](build-and-deployment.md) | [한국어](build-and-deployment.ko.md)

# Build and deployment

Generated projects own their build lifecycle. The WIZ Spring generator JAR is not a
build plugin and is not needed in an application runtime.

## Project commands

Install the lockfile dependencies after a fresh generation, then use the common
scripts in every template:

```bash
npm ci
npm run frontend:build
npm run backend:build
npm run build
npm run dev
npm run bundle
```

| Script | Result |
| --- | --- |
| `frontend:build` | Build only the selected frontend. |
| `backend:build` | Package the backend without deleting the independently watched frontend output. |
| `build` | Clean-build the backend and frontend. |
| `dev` | Initial-build and watch Spring plus frontend output served from the Spring port. |
| `bundle` | Build and atomically publish a deployment bundle. |

The committed Maven Wrapper also builds the backend directly:

```bash
./mvnw clean package
```

Angular WIZ additionally exposes `npm run wizbuild` and `npm run wizwatch`. Its
compiler lives in `scripts/wizbuild.mjs`, `scripts/wizwatch.mjs`, and `scripts/wiz/`.
It is source committed into the generated project; there is no external WIZ frontend
package.

Keep `npm run dev` running during ordinary source edits. The backend watcher compiles Java
and resources, then updates a dedicated DevTools trigger only after compilation succeeds.
The last good application therefore stays up on compile failure, and a successful build
causes one restart inside the same command lifecycle. Every frontend watcher writes successful builds to
`target/generated-resources/frontend`, so refreshing the Spring URL uses the new output.
`npm run backend:build` preserves that output; the integrated `npm run build` performs its
clean backend build first and signals the live application only after the frontend rebuild
also succeeds.
Standard Angular and React projects retain `npm run frontend:serve` as an optional separate
HMR server. Dependency and build-configuration changes still require a full build and a
development-process restart because they change the process classpath or watcher itself.

## Environment configuration

Every generated project already contains `<project>/.env` with runnable defaults. Edit that
file directly; there is no example-file copy step. Every generated npm script loads it, and
an already exported process variable takes precedence. The default development service reads
the same file through systemd. Keep the checked-in defaults non-secret; inject credentials
through the process environment or an external service `--env-file`.

The most common settings are `SERVER_PORT`, `APP_API_PREFIX`,
`SPRING_PROFILES_ACTIVE`, and the `APP_DATASOURCE_*` values listed in the example.
Service options `--port` and `--profiles` override their environment equivalents.
Use `--env-file /absolute/path/service.env` when configuration must live elsewhere;
relative values are resolved from the project root (or bundle root in production).

Source edits are watched live. Environment values are process-start configuration, so run
`systemctl restart wiz.<name>` after editing the `.env` used by an installed service.

## API prefix and path versions

Business controllers declare only their resource path:

```java
@ApiController("/dashboard")
public class DashboardController {
    @GetMapping
    public String dashboard() {
        return "ready";
    }
}
```

Generated Spring MVC configuration applies the global prefix centrally:

```yaml
app:
  api:
    prefix: ${APP_API_PREFIX:/api}
```

The example maps to `/api/dashboard`. Set `APP_API_PREFIX=/api/v2` to change the
prefix without editing controllers. The frontend reads the resolved client prefix from
`/app-config.json` at runtime.

For simultaneous versions, set `APP_API_VERSIONING_MODE=path`, provide
`APP_API_DEFAULT_VERSION`, configure the supported versions, and declare a version on
the controller mapping. The prefix remains `/api`; Spring path versioning adds the
version segment.

## Bundle layout

`npm run bundle` publishes backend and frontend artifacts from the same source revision:

```text
bundle/
├── app/application.jar     # application.war for JSP
├── public/
├── config/
├── data/                    # mutable runtime database directory
├── deploy/
│   ├── nginx/
│   ├── apache2/
│   └── docker/
├── docker-compose.yaml
├── run.sh
├── .env
├── manifest.json
└── SHA256SUMS
```

Verify a bundle before deployment:

```bash
cd bundle
sha256sum -c SHA256SUMS
./run.sh
```

JSP uses an executable WAR because Spring Boot does not support JSP in an executable
JAR. The other templates produce an executable JAR and an independent frontend tree.
The copied `bundle/` is self-contained apart from JDK 25+ and a POSIX shell: running
`./run.sh` needs no Node.js, npm, Maven, or WIZ Spring installation. Inherited environment
variables override `.env`; Spring command-line arguments can be appended to `./run.sh`.
Checksums cover immutable application/configuration files; `.env` and `data/` are the two
documented runtime-mutable locations.

## Docker Compose

Edit the generated `bundle/.env` directly when needed, then run exactly one reverse-proxy profile:

```bash
cd bundle
docker compose --profile nginx up -d
# or
docker compose --profile apache2 up -d
```

The backend container runs as UID/GID `10001`. The provided proxy configuration keeps
SSE responses unbuffered. TLS, certificate provisioning, secrets, and production data
storage remain deployment responsibilities; pass them through environment variables or
external Spring configuration.

## systemd service

The default service mode is live development. It runs the generated project's own
`npm run dev`, so source changes are rebuilt and applied without reinstalling or manually
restarting the service:

```bash
wiz-spring service install dashboard \
  --root /srv/dashboard \
  --user dashboard
```

Use production mode only for an immutable deployment. Run `npm run bundle` first, then:

```bash
wiz-spring service install dashboard \
  --production \
  --root /srv/dashboard \
  --user dashboard
```

`--production` uses `<root>/bundle`; `--bundle /another/path` both selects production mode
and overrides the bundle. The installed production unit executes the bundle artifact
directly. The installer writes an absolute `npm` or `java` command into the launcher;
neither installed mode invokes or requires the WIZ Spring executable or generator JAR.
Development defaults to Spring profile `dev`; production defaults to `prod,bundle`. Both
modes write to journald, accept `--profiles`, load their default `.env`, and are enabled and
started immediately. `--port`, `--profiles`, and an explicitly exported unit environment
take precedence over defaults; use `--env-file` to select another configuration file.

A root-owned project or bundle requires either a non-root `--user` or explicit `--allow-root`
acknowledgement. Management commands include `list`, `status`, `logs`, `start`, `stop`,
`restart`, and `uninstall`. Use `wiz-spring service <command> --help` for all options.

## Related documentation

- [Project generation and imports](project-generation.md)
- [Generated deployment instructions](../src/main/resources/wiz/templates/project-common/deploy/README.md)
- [AI build and deployment contract](../src/main/resources/wiz/templates/project-common/docs/ai/deployment.md)
