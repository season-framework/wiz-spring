[English](ai-instructions.md) | [한국어](ai-instructions.ko.md)

# AI instructions

Every generated project receives the common project, Spring backend, and deployment
contracts plus exactly one frontend contract. These files describe the current 1.0
layout to AI coding tools and human contributors; they do not add an MCP runtime.

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
2. Keep paths, build commands, and API conventions aligned with the template code.
3. Run `./mvnw test`; generator tests verify instruction boundaries while creating
   temporary projects.
4. Generate the affected template and inspect the resulting instruction files before a
   release.

See [Project generation](project-generation.md) for template layouts and
[Build and deployment](build-and-deployment.md) for the contracts these instructions
describe.
