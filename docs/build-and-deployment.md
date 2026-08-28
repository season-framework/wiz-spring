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
| `backend:build` | Run the Maven clean/package workflow. |
| `build` | Clean-build the backend and frontend. |
| `dev` | Run Spring, the backend compile watcher, and the frontend watcher together. |
| `bundle` | Build and atomically publish a deployment bundle. |

The committed Maven Wrapper also builds the backend directly:

```bash
./mvnw clean package
```

Angular WIZ additionally exposes `npm run wizbuild` and `npm run wizwatch`. Its
compiler lives in `scripts/wizbuild.mjs`, `scripts/wizwatch.mjs`, and `scripts/wiz/`.
It is source committed into the generated project; there is no external WIZ frontend
package.

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
├── deploy/
│   ├── nginx/
│   ├── apache2/
│   └── docker/
├── docker-compose.yaml
├── manifest.json
└── SHA256SUMS
```

Verify a bundle before deployment:

```bash
cd bundle
sha256sum -c SHA256SUMS
```

JSP uses an executable WAR because Spring Boot does not support JSP in an executable
JAR. The other templates produce an executable JAR and an independent frontend tree.

## Docker Compose

Copy `bundle/.env.example` to `bundle/.env`, then run exactly one reverse-proxy profile:

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

Install a completed bundle with the generator CLI:

```bash
wiz-spring service install dashboard \
  --bundle /srv/dashboard/bundle \
  --user dashboard
```

The installed unit runs the bundle artifact directly and is enabled for reboot. It does
not call the generator JAR. Defaults are Spring profiles `prod,bundle` and journald
output; `--profiles` overrides the profile list.

A root-owned bundle requires either a non-root `--user` or explicit `--allow-root`
acknowledgement. Management commands include `list`, `status`, `logs`, `start`, `stop`,
`restart`, and `uninstall`. Use `wiz-spring service <command> --help` for all options.

## Related documentation

- [Project generation and imports](project-generation.md)
- [Generated deployment instructions](../src/main/resources/wiz/templates/project-common/deploy/README.md)
- [AI build and deployment contract](../src/main/resources/wiz/templates/project-common/docs/ai/deployment.md)
