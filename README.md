[English](README.md) | [한국어](README.ko.md)

<div align="center">

# WIZ Spring

**Create a standard Spring backend with the frontend structure that fits the project.**

Java 21 · Spring Boot 4 · Angular WIZ · Angular · React · HTML · JSP

</div>

`wiz-spring` 1.0.0 is a project generator and an optional systemd service manager. A
generated project builds, watches, runs, and bundles itself with Maven and the scripts
committed into the project. The generator JAR is needed only to create a project or to
manage its service; it is not part of the build or application runtime.

No `.wiz` directory or private WIZ NPM build package is created.

## Compatibility boundary with 0.2.x

> **Important:** 1.0.0 is a clean break, not an in-place upgrade from the 0.2.x
> line, including 0.2.8.

WIZ Spring 1.0.0 does not automatically detect, convert, or migrate a 0.2.8
workspace's WIZ backend transformation model, runtime configuration, build or bundle
artifacts, or existing systemd units. Keep the matching 0.2.8 runtime and tooling for
an existing project, or create a new 1.0 project and port the code manually into the
standard Spring and selected frontend layouts. Do not use the 1.0
`create`/import/`service` commands as a direct upgrade path for a 0.2.8 workspace or
bundle.

## Build the generator

```bash
./mvnw clean package
alias wiz-spring='java -jar /absolute/path/to/wiz-spring/target/wiz-spring-1.0.0.jar'
```

## Create a project

Angular WIZ is the default template.

`create` verifies the complete local build toolchain before writing any project files:
JDK 21 or newer, Node.js `^22.22.3 || ^24.15.0 || ^26.0.0`, and npm 10 or newer.
Node.js releases outside the generated Angular toolchain's supported major and patch
ranges are rejected even when their numeric version is newer.

```bash
wiz-spring create ../dashboard --package com.example.dashboard

wiz-spring create ../site \
  --package com.example.site \
  --template react
```

Available templates:

```text
angular-wiz (default)
angular
react
html
jsp
```

Run `wiz-spring templates` to see the template descriptions.

## CLI commands

The 1.0 CLI deliberately has a small surface. Building and running belong to each
generated project rather than to the generator.

| Command | Description |
| --- | --- |
| `create <path> --package <package> [--template <template>]` | Create a standalone Spring project. |
| `templates` | List available frontend templates. |
| `service <subcommand>` | Install and manage a generated bundle as a systemd service. |
| `completion <bash\|zsh>` | Print a shell completion script. |

Use the CLI itself for the complete option list:

```bash
wiz-spring --help
wiz-spring create --help
wiz-spring service --help
```

Enable completion in the current shell with one of these commands:

```bash
# Bash
source <(wiz-spring completion bash)

# Zsh
source <(wiz-spring completion zsh)
```

## Generated-project commands

```bash
npm ci
npm run frontend:build
npm run backend:build       # equivalent to Maven clean package
npm run build               # backend + frontend
npm run dev                 # Spring + backend compile watcher + frontend watcher
npm run bundle
```

Angular WIZ also exposes explicit frontend commands:

```bash
npm run wizbuild
npm run wizwatch
```

The WIZ compiler is committed as `scripts/wizbuild.mjs` and `scripts/wiz/*.mjs`.
After a Git clone it needs only the lockfile dependencies; no `wiz-spring` JAR and no
external WIZ build package are involved.

The committed Maven Wrapper builds the backend directly:

```bash
./mvnw clean package
```

## Backend and API paths

Generated backend code is ordinary Maven/Spring source under `src/main/java`. There is
no source relocation, runtime Java compilation, reflective app dispatcher, or
frontend-metadata-based API generation.

Controllers omit the global prefix:

```java
@ApiController("/dashboard")
public class DashboardController {
    @GetMapping
    public DashboardResponse dashboard() { /* ... */ }
}
```

The generated Spring MVC configuration applies the prefix centrally:

```yaml
app:
  api:
    prefix: ${APP_API_PREFIX:/api}
```

This maps the example to `/api/dashboard`. Setting `APP_API_PREFIX=/api/v2` maps it to
`/api/v2/dashboard` without editing the controller. Optional Spring path versioning is
configured under `app.api.versioning` for simultaneous versions and requires a
`default-version`. The frontend reads the resolved client prefix from
`/app-config.json` at runtime.

### Fresh-project sample

A newly generated project is a usable reference application rather than a one-endpoint
placeholder. Its standard Spring backend includes session login with BCrypt, five seeded
members, post search and CRUD, profile/password changes, dashboard statistics, persisted
H2 data, chat history, and an SSE chat stream. Every frontend template exposes the same
responsive login, dashboard, members, posts, profile, chat, and light/dark-theme flow.

```text
admin@example.com / admin1234
```

The sample source and its tests are injected only for a fresh project. `--uri` and
`--path` imports receive the selected build/API-prefix infrastructure without having
demo controllers or screens mixed into existing application source.

Imports must already use the selected 1.0 frontend layout: `src/app/` for Angular WIZ,
`frontend/src/{index.html,main.ts,styles.css}` for Angular, `frontend/index.html` plus
`frontend/src/` for React, `frontend/index.html` for HTML, or
`src/main/webapp/WEB-INF/jsp/` for JSP. A mismatch fails before the target is published;
the generator does not guess or relocate legacy layouts.
In particular, `--path` and `--uri` imports are not migration commands for 0.2.8
projects.

## Frontend identification

Generated `package.json` contains `wiz.frontend`. Tools use that explicit value first,
then fall back to standard project evidence such as `angular.json`, React dependencies,
or `src/main/webapp/WEB-INF` for JSP. A metadata/layout mismatch fails instead of
silently selecting another builder.

Angular WIZ retains the source layout designed for human and AI editing:

```text
src/app/
src/portal/
src/route/
src/angular/
```

Only frontend files live there. Java belongs under `src/main/java`.

## Bundle and containers

`npm run bundle` performs a clean backend/frontend build and publishes an atomic bundle:

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

Run one proxy profile:

```bash
docker compose --profile nginx up -d
docker compose --profile apache2 up -d
```

JSP uses an executable WAR because Spring Boot does not support JSP in an executable
JAR. The other templates generate an executable JAR and an independent frontend tree.

## systemd service

```bash
wiz-spring service install dashboard \
  --bundle /srv/dashboard/bundle \
  --user dashboard
```

The installed unit executes the bundle artifact directly and is enabled with systemd.
It continues to start after reboot even if the generator JAR is no longer installed.
The default Spring profiles are `prod,bundle`, service output goes to journald, and
`--profiles` can override the active profile list. For safety, a root-owned bundle
requires either a non-root `--user` or the explicit `--allow-root` acknowledgement.
`list`, `status`, `logs`, `start`, `stop`, `restart`, and `uninstall` are available.

## AI instructions

Every generated project receives the common instruction set and exactly one frontend
instruction file. These are the authoritative source and generated-project locations:

| Scope | Generator source | Generated project |
| --- | --- | --- |
| Root project contract | [`project-common/AGENTS.md`](src/main/resources/wiz/templates/project-common/AGENTS.md) | `AGENTS.md` |
| Copilot entry point | [`project-common/.github/copilot-instructions.md`](src/main/resources/wiz/templates/project-common/.github/copilot-instructions.md) | `.github/copilot-instructions.md` |
| Spring backend | [`project-common/docs/ai/backend-spring.md`](src/main/resources/wiz/templates/project-common/docs/ai/backend-spring.md) | `docs/ai/backend-spring.md` |
| Build and deployment | [`project-common/docs/ai/deployment.md`](src/main/resources/wiz/templates/project-common/docs/ai/deployment.md) | `docs/ai/deployment.md` |
| Angular WIZ frontend | [`project-angular-wiz/docs/ai/frontend.md`](src/main/resources/wiz/templates/project-angular-wiz/docs/ai/frontend.md) | `docs/ai/frontend.md` |
| Angular frontend | [`project-angular/docs/ai/frontend.md`](src/main/resources/wiz/templates/project-angular/docs/ai/frontend.md) | `docs/ai/frontend.md` |
| React frontend | [`project-react/docs/ai/frontend.md`](src/main/resources/wiz/templates/project-react/docs/ai/frontend.md) | `docs/ai/frontend.md` |
| HTML frontend | [`project-html/docs/ai/frontend.md`](src/main/resources/wiz/templates/project-html/docs/ai/frontend.md) | `docs/ai/frontend.md` |
| JSP frontend | [`project-jsp/docs/ai/frontend.md`](src/main/resources/wiz/templates/project-jsp/docs/ai/frontend.md) | `docs/ai/frontend.md` |

The chosen frontend overlay supplies `docs/ai/frontend.md`; the other frontend guides
are not copied. No project-local MCP runtime is configured.

## Development

```bash
./mvnw test
```

The generator tests create each template in a temporary directory. Before a release,
also smoke-test the generated Maven project, selected frontend build, and bundle
validation.

## License

WIZ Spring is licensed under the [MIT License](LICENSE).
