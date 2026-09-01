<div align="center">

# WIZ Spring

**Generate a standard Spring Boot backend with the frontend structure your project needs.**

[![Release 1.1.1](https://img.shields.io/badge/release-1.1.1-2563eb)](release-log/1.1.1.md)
[![Java 25+](https://img.shields.io/badge/Java-25%2B-e76f00)](pom.xml)
[![MIT License](https://img.shields.io/badge/license-MIT-16a34a)](LICENSE)

[English](README.md) · [한국어](README.ko.md)

</div>

WIZ Spring is a project generator and optional systemd service manager for Spring Boot
4 with Angular WIZ, Angular, React, HTML, or JSP. The generator JAR is needed only for
project creation or service administration; the generated project owns its Maven,
frontend, watch, build, bundle, and runtime workflows.

> [!IMPORTANT]
> WIZ Spring 1.0 is a clean break from 0.2.x, including 0.2.8. It does not migrate a
> legacy workspace in place. Read the [1.0 compatibility guide](docs/compatibility.md)
> before moving an existing application.

## 1.1.1 platform baseline

WIZ Spring `1.1.1` generates and validates the following stack. These are concrete
template versions, not merely minimum compatibility claims.

| Layer | 1.1.1 baseline |
| --- | --- |
| Generator and generated project version | `1.1.1` |
| Java | release `25`; full JDK 25 or newer required |
| Spring backend | Spring Boot `4.1.1`, Boot-managed Spring Framework `7.0.9`, springdoc `3.1.0` |
| Build tools | Maven Wrapper `3.9.15`, npm `10+` |
| Node.js | `^22.22.3 || ^24.15.0` (LTS releases only) |
| Angular templates | Angular `22.1.4`, Angular CLI/build `22.1.6`, TypeScript `6.0.3` |
| React template | React `19.2.8`, Vite `8.2.2` |

Updating the generator does not rewrite an already generated project. To adopt this
baseline, create a new `1.1.1` project or deliberately update that project's `pom.xml`,
`package.json`, lockfile, and `docs/ai` instructions together.

## Why WIZ Spring

- **Standard backend** — Java stays under `src/main/java` and builds directly with
  Maven; there is no WIZ backend transformation or runtime dispatcher.
- **Shallow domain structure** — Controllers enter through a typed root `Struct`, and
  each feature keeps behavior and persistence together in one `model/<feature>` package.
- **Five frontend choices** — use a conventional frontend or keep the Angular WIZ
  layout designed for fast human and AI editing.
- **Standalone projects** — after `create`, builds need neither the generator JAR nor
  an external WIZ NPM package. No `.wiz` directory is created.
- **One delivery workflow** — every template provides watch, integrated build, bundle,
  Docker Compose, Nginx/Apache2, and optional systemd service support.

## Quick start

Requirements: full JDK 25+, Node.js `^22.22.3 || ^24.15.0` (LTS only), and npm 10+.

```bash
./mvnw clean package

java -jar target/wiz-spring-1.1.1.jar create ../dashboard \
  --package com.example.dashboard

cd ../dashboard
npm ci
npm run dev
```

`angular-wiz` is the default template. Add `--template react` or another template ID
to select a different frontend. The target directory name is normalized into a
lowercase Maven/npm artifact ID, so project names can contain `-`; Java package
segments cannot because they must be valid Java identifiers.

## Templates

| ID | Best fit |
| --- | --- |
| `angular-wiz` | Angular with the embedded WIZ source layout and compiler; default |
| `angular` | Standard Angular application |
| `react` | React application built with Vite |
| `html` | Static HTML, CSS, and JavaScript |
| `jsp` | Server-rendered Spring MVC/JSP application |

Run `java -jar target/wiz-spring-1.1.1.jar templates` for the built-in descriptions.

## Generated workflow

```bash
npm run dev       # Spring + backend compile watcher + frontend watcher
npm run build     # clean backend and frontend build
npm run bundle    # deployable artifact, proxy configs, Compose, checksums
```

Every template also exposes `frontend:build` and `backend:build`. Angular WIZ adds
`wizbuild` and `wizwatch`; its compiler is committed into the generated project.

## CLI

| Command | Purpose |
| --- | --- |
| `create` | Create a new project or import compatible 1.0 source. |
| `templates` | List frontend templates. |
| `service` | Install and manage a generated bundle with systemd. |
| `completion` | Generate Bash or Zsh completion. |

Use `<command> --help` for the complete options.

## HTTP project helper

The optional [Docker project helper](helper/README.md) exposes project generation over
HTTP and returns a ZIP. Its build-time registry can add, customize, or remove templates
without packaging the helper into `wiz-spring-1.1.1.jar`.

## Documentation

| Guide | Contents |
| --- | --- |
| [Project generation](docs/project-generation.md) | Requirements, templates, imports, sample application, and CLI |
| [Build and deployment](docs/build-and-deployment.md) | Project scripts, API prefixes, bundles, Compose, and systemd |
| [1.0 compatibility](docs/compatibility.md) | The supported transition from 0.2.x |
| [AI instructions](docs/ai-instructions.md) | Common and template-specific instruction sources |
| [HTTP helper](helper/README.md) | API usage, custom registries, and container operations |
| [Release notes](release-log/README.md) | Version history |

## Development

```bash
./mvnw test
scripts/verify-documentation.sh
scripts/verify-templates.sh
```

Tests generate every frontend template in disposable directories. Helper-specific
checks are documented in the [helper operations guide](helper/docs/operations.md).
The template verification script installs, audits, tests, builds, and bundles all five
generated templates; it requires the same JDK and Node.js toolchain as generated projects.

Bugs and feature requests are tracked in [GitHub Issues](https://github.com/season-framework/wiz-spring/issues).
WIZ Spring is available under the [MIT License](LICENSE).
