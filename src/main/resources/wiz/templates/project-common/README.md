# __WIZ_PROJECT_NAME__

Generated as a standard Spring Boot project with the `__WIZ_FRONTEND__` frontend template.
The generated project has no runtime dependency on `wiz-spring`.

## Requirements

- JDK 25 or newer (a full JDK with `javac`)
- Node.js `^22.22.3 || ^24.15.0 || >=26.0.0`
- npm 8 or newer

The generator requires the full JDK before project creation. Node.js and npm checks are
advisory and never block creation. The requirements remain visible in `package.json` so a
fresh clone and its package manager can report the policy before a build.

## Generated platform baseline

This project was generated from WIZ Spring `1.2.2` with the following backend and
build baseline:

| Layer | Version or policy |
| --- | --- |
| Java compilation | release `25` |
| Spring backend | Spring Boot `4.1.1`, Boot-managed Spring Framework `7.0.9` |
| API documentation | springdoc `3.1.0` |
| Maven | Wrapper `3.9.15` |
| Node.js | `^22.22.3 || ^24.15.0 || >=26.0.0` |
| npm | `8+` |

The exact frontend dependency versions are documented in `docs/ai/frontend.md` and
pinned in `package.json` and `package-lock.json`. This project is standalone: installing
a newer WIZ Spring generator does not update it. When changing the platform, update
`pom.xml`, npm manifests and lockfiles, this README, and `docs/ai` in the same change.

## Commands

```bash
npm ci
npm run frontend:build
npm run backend:build
npm run build
npm run dev
npm run bundle
```

`npm run dev` performs the initial backend/frontend builds and keeps both outputs current.
Java and resource edits are compiled automatically and Spring DevTools restarts the
application only after a successful compile, inside the same command lifecycle. A failed
compile leaves the last good application running. Angular and React projects also write their
watched frontend builds to `target/generated-resources/frontend`, so the Spring port never
serves an older production build. A normal source edit does not require stopping and
restarting the command; wait for the watcher to report a successful rebuild. The backend-only
build preserves the watched frontend output, while the integrated build requests a restart
only after both backend and frontend builds succeed.

`npm ci` is for a freshly generated project. If this project was created with
`--uri` or `--path`, run `npm install` once instead so its imported dependency state
is reconciled with the injected template dependencies; commit the resulting lockfile,
then use `npm ci` normally.

For imported projects, the root `pom.xml`, current files in `docs/ai`, and the
selected frontend build configuration come from this template. Replaced standard files
are kept as inactive reference copies under `replaced-originals/`. Review an archived
`pom.xml` and manually merge any dependencies or plugins the application still needs;
the archived POM itself is never used by the build.

An import is accepted only when backend Java already lives under `src/main/java` in
the requested package and the source is already a standalone Spring/frontend project.
Imports are validated against that current contract without legacy detection or migration.
The selected frontend must already use its 1.0 source root: `src/app/` (Angular WIZ),
`frontend/src/` with `index.html`, `main.ts`, and `styles.css` (Angular),
`frontend/index.html` plus `frontend/src/` (React), `frontend/index.html` (HTML), or
`src/main/webapp/WEB-INF/jsp/` (JSP).

The committed Maven Wrapper pins Maven `3.9.15`, so the backend can also be built
directly with `./mvnw clean package` (`mvnw.cmd clean package` on Windows).

Business APIs start at `/api` by default. Change `app.api.prefix` or the
`APP_API_PREFIX` environment variable without editing controllers.
The optional standard Angular and React development servers use the same variable, for
example `APP_API_PREFIX=/api/v2 npm run frontend:serve`.

## Environment configuration

The generated root `.env` already contains runnable defaults. Edit that file directly; no
copy or rename step is required. Every npm script loads it automatically, and an environment
variable exported before the command takes precedence. Keep this source-controlled defaults
file free of secrets; inject credentials through the process environment or an external
service `--env-file`. Common settings include `SERVER_PORT`, `APP_API_PREFIX`,
`SPRING_PROFILES_ACTIVE`, and `APP_DATASOURCE_*`.

The default development systemd service also reads `<project>/.env`. A production service
reads `<bundle>/.env`. Use `--env-file` to choose another location; `--port` and `--profiles`
override file values. Source files reload automatically, but environment values are read at
process start, so restart an installed service after editing its `.env`.

## Included sample application (fresh projects)

The project includes a complete Spring Data JPA sample backend backed by a persistent
H2 database in `data/`. It seeds five members and three posts on first run. Use the
administrator account to try every screen:

```text
email: admin@example.com
password: admin1234
```

The sample API covers session login/logout, dashboard statistics, member management,
post search and CRUD, profile/password updates, chat history, and an SSE chat stream.
Under the default `dev` profile, open `/swagger-ui` for the generated contract.
The `prod` profile disables API docs and Swagger UI unless
`SPRINGDOC_API_DOCS_ENABLED=true` and `SPRINGDOC_SWAGGER_UI_ENABLED=true` are set.
Important routes are:

- `/api/auth/session`, `/api/auth/login`, `/api/auth/logout`
- `/api/dashboard`
- `/api/members`, `/api/members/{id}`
- `/api/posts`, `/api/posts/categories`, `/api/posts/{id}`
- `/api/profile`, `/api/profile/password`
- `/api/chat/messages`, `/api/chat/stream`

The backend follows a shallow, feature-oriented Spring structure:

```text
controller -> model.Struct -> model/<feature>/<Feature>Struct -> Repository
```

Each feature keeps its Struct, Entity, Repository, and safe response records together at
one depth. Endpoint-only request records stay inside Controllers; `config`, `security`,
`exception`, and `web` contain named infrastructure responsibilities. See
`docs/ai/backend-spring.md` before changing this boundary.

Invited sample members receive the initial password `welcome1`. Override the default
database with `APP_DATASOURCE_URL`, `APP_DATASOURCE_USERNAME`, and
`APP_DATASOURCE_PASSWORD`. Production applications should replace the demo invitation
password flow and configure secure session-cookie transport behind HTTPS.

When this project was created with `--uri` or `--path`, wiz-spring intentionally did
not inject the demo controllers, domain, database, tests, or frontend screens into the
imported application.

Deployment output is written to `bundle/`. See `deploy/README.md`.
It contains only the root archive (`application.jar`, or `application.war` for JSP),
`public/`, `.env`, and `docker-compose.yaml`. On a server with JDK 25+, load its
environment and run the archive:

```bash
cd bundle
set -a
. ./.env
set +a
java -jar "$APP_ARTIFACT"
```

`docker compose up -d` runs the same prebuilt application as a single JRE service. Nginx
and Apache HTTP Server are not part of Compose or the bundle; editable host configuration
examples are kept in `deploy/nginx/default.conf.example` and
`deploy/apache2/wiz.conf.example`.

For a continuously updated systemd service, install the project root in the default
development mode:

```bash
wiz-spring service install __WIZ_ARTIFACT_ID__ --root . --user <service-user>
```

For an immutable deployment, first run `npm run bundle`, then add `--production`.
The installed launcher calls the project's absolute npm executable in development or Java
directly in production; it never calls or depends on the WIZ Spring CLI after installation.
