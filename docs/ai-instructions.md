[English](ai-instructions.md) | [한국어](ai-instructions.ko.md)

# AI instructions

Every generated project receives the common project, Spring backend, and deployment
contracts plus exactly one frontend contract. These files describe the current `1.1.1`
project contract to AI coding tools and human contributors; they do not add an MCP
runtime.

The instruction baseline is Java 25, Spring Boot `4.1.1`, Spring Framework `7.0.9`
(managed by the Boot BOM), springdoc `3.1.0`, Maven `3.9.15`, and Node.js
`^22.22.3 || ^24.15.0`. Frontend-specific instructions also name
the pinned Angular 22 or React 19 toolchain. The generated `pom.xml`, `package.json`, and
lockfile remain authoritative if application owners later upgrade their standalone
project.

| Scope | Generator source | Generated project |
| --- | --- | --- |
| Root project contract | [`project-common/AGENTS.md`](../src/main/resources/wiz/templates/project-common/AGENTS.md) | `AGENTS.md` |
| Copilot entry point | [`project-common/.github/copilot-instructions.md`](../src/main/resources/wiz/templates/project-common/.github/copilot-instructions.md) | `.github/copilot-instructions.md` |
| Spring backend | [`project-common/docs/ai/backend-spring.md`](../src/main/resources/wiz/templates/project-common/docs/ai/backend-spring.md) | `docs/ai/backend-spring.md` |
| Build and deployment | [`project-common/docs/ai/deployment.md`](../src/main/resources/wiz/templates/project-common/docs/ai/deployment.md) | `docs/ai/deployment.md` |
| Angular WIZ frontend | [`project-angular-wiz/docs/ai/frontend.md`](../src/main/resources/wiz/templates/project-angular-wiz/docs/ai/frontend.md) | `docs/ai/frontend.md` |
| Angular frontend | [`project-angular/docs/ai/frontend.md`](../src/main/resources/wiz/templates/project-angular/docs/ai/frontend.md) | `docs/ai/frontend.md` |
| React frontend | [`project-react/docs/ai/frontend.md`](../src/main/resources/wiz/templates/project-react/docs/ai/frontend.md) | `docs/ai/frontend.md` |
| HTML frontend | [`project-html/docs/ai/frontend.md`](../src/main/resources/wiz/templates/project-html/docs/ai/frontend.md) | `docs/ai/frontend.md` |
| JSP frontend | [`project-jsp/docs/ai/frontend.md`](../src/main/resources/wiz/templates/project-jsp/docs/ai/frontend.md) | `docs/ai/frontend.md` |

The selected frontend overlay supplies `docs/ai/frontend.md`; contracts for the other
frontends are not copied. Keep the table above as the canonical source map when an
instruction needs to change.

## Updating an instruction

1. Edit the generator source for the relevant common or frontend scope.
2. Keep versions, paths, build commands, and API conventions aligned with the template
   code. Do not update only a README or only an instruction.
3. Run `./mvnw test`; generator tests verify instruction and version boundaries while creating
   temporary projects.
4. Run `scripts/verify-documentation.sh` and generate the affected template. Inspect the
   resulting README and instruction files before a release.
5. Run `scripts/verify-templates.sh` when a platform or dependency version changes.

See [Project generation](project-generation.md) for template layouts and
[Build and deployment](build-and-deployment.md) for the contracts these instructions
describe.
