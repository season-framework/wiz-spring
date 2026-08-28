[English](project-generation.md) | [한국어](project-generation.ko.md)

# Project generation

WIZ Spring creates a standard Spring Boot project with one selected frontend. The
generator is not required after creation: build, watch, run, and bundle commands are
committed into the generated repository.

## Requirements

- A full JDK 21 or newer, including `javac`
- Node.js `^22.22.3 || ^24.15.0 || ^26.0.0`
- npm 10 or newer
- Git on `PATH` when importing with `--uri`

`create` checks the complete toolchain before writing the target. Node.js versions
outside the supported Angular toolchain ranges are rejected even when their numeric
version is newer.

Build the generator from source:

```bash
./mvnw clean package
alias wiz-spring='java -jar /absolute/path/to/wiz-spring/target/wiz-spring-1.0.0.jar'
```

## CLI

| Command | Purpose |
| --- | --- |
| `create <path> --package <package> [--template <id>]` | Create or import a standalone project. |
| `templates` | List built-in frontend templates. |
| `service <subcommand>` | Manage a generated bundle as a systemd service. |
| `completion <bash\|zsh>` | Generate shell completion. |

Use `wiz-spring <command> --help` as the option reference. Enable completion in the
current shell with `source <(wiz-spring completion bash)` or
`source <(wiz-spring completion zsh)`.

## Templates

`angular-wiz` is the default.

| ID | Frontend source | Notes |
| --- | --- | --- |
| `angular-wiz` | `src/app`, `src/portal`, `src/route`, `src/angular` | Angular with the embedded WIZ source compiler and AI-friendly layout |
| `angular` | `frontend/src` | Standard Angular application |
| `react` | `frontend/src` | React application built with Vite |
| `html` | `frontend` | Static HTML, CSS, and JavaScript |
| `jsp` | `src/main/webapp/WEB-INF/jsp` | Spring MVC with an executable WAR |

Every template uses a standard Maven backend under `src/main/java`. No `.wiz`
directory or external WIZ build package is created. The root `package.json` records
the selected frontend in `wiz.frontend`; build tools validate that metadata against
the source layout instead of silently choosing another builder.

The target directory name is lowercased to form the Maven/npm artifact ID. Runs of
characters outside `[a-z0-9_.-]` become `-`, and leading or trailing separators are
removed. A Java package is made of Java 21 identifiers, so a package segment cannot
contain `-` and cannot be a Java keyword or use the `java` namespace.

## Creating a new project

```bash
wiz-spring create ../dashboard --package com.example.dashboard

wiz-spring create ../portal \
  --package com.example.portal \
  --template react
```

A fresh project includes a working reference application: session login with BCrypt,
five members, posts and search, profile/password updates, dashboard statistics,
persistent H2 data, chat history, and an SSE chat stream. Each frontend implements the
same responsive screens and light/dark theme.

```text
admin@example.com / admin1234
```

The generated project documents its API at `/swagger-ui` when running.

## Importing existing source

Use exactly one import source and always select a template explicitly:

```bash
wiz-spring create ../imported \
  --package com.example.imported \
  --template angular \
  --path /absolute/path/to/source

wiz-spring create ../imported \
  --package com.example.imported \
  --template react \
  --uri https://example.com/team/project.git
```

The source must already follow the 1.0 contract:

- If Java source is present, it is under `src/main/java` in the requested package.
- The source either has no `@SpringBootApplication`, or has exactly one at the
  requested package root as `Application.java`. The generator supplies it when absent.
- The frontend uses the selected template's source path shown above.

Import validation runs before publication. WIZ Spring does not detect legacy versions,
guess a frontend, or relocate incompatible source. Standard files replaced by the 1.0
build infrastructure are archived under `replaced-originals/` for manual review.
Imported projects should run `npm install` once to reconcile their dependency state,
commit the resulting lockfile, and use `npm ci` afterward.

Fresh-project samples are not injected into imports. See
[1.0 compatibility](compatibility.md) before moving a 0.2.x application.

## Next steps

- [Build, run, bundle, and deploy](build-and-deployment.md)
- [AI instruction layout](ai-instructions.md)
- [HTTP project helper](../helper/README.md)
