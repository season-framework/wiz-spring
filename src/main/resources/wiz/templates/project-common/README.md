# __WIZ_PROJECT_NAME__

Generated as a standard Spring Boot project with the `__WIZ_FRONTEND__` frontend template.
The generated project has no runtime dependency on `wiz-spring`.

## Requirements

- JDK 25 or newer (a full JDK with `javac`)
- Node.js `^22.22.3 || ^24.15.0` (LTS releases only)
- npm 10 or newer

The generator checks these tools before project creation. The same requirements remain
visible in `package.json` so a fresh clone and its package manager can report the policy.

## Commands

```bash
npm ci
npm run frontend:build
npm run backend:build
npm run build
npm run dev
npm run bundle
```

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

The committed Maven Wrapper pins Maven 3.9.15, so the backend can also be built
directly with `./mvnw clean package` (`mvnw.cmd clean package` on Windows).

Business APIs start at `/api` by default. Change `app.api.prefix` or the
`APP_API_PREFIX` environment variable without editing controllers.
The standard Angular and React development servers use the same variable, for
example `APP_API_PREFIX=/api/v2 npm run dev`.

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
