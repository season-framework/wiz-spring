# Project AI instructions

The current project contract is defined by `AGENTS.md` at the repository root.
It is the WIZ Spring `1.2.0` contract: Java release 25, Spring Boot `4.1.1`,
Boot-managed Spring Framework `7.0.9`, springdoc `3.1.0`, Maven Wrapper `3.9.15`, and the Node.js LTS range declared in
`package.json`. Do not infer the retired 0.2.x WIZ runtime model from older examples.
Before changing structure, also read the focused guides in `docs/ai/`:

- `docs/ai/backend-spring.md`
- `docs/ai/frontend.md`
- `docs/ai/deployment.md`

Never stop at source edits. Run the relevant backend/frontend tests and build commands from
`AGENTS.md`, and verify that an active `npm run dev` watcher reports a successful rebuild
and Spring restart before declaring the change complete. Ordinary source edits must not
require manually stopping and restarting the development process.

Use the generated root `.env` directly for non-secret project defaults; do not introduce an
environment-file copy or rename step. Inject secrets through exported variables or an external
service env file.
Deployment bundles must start through `./run.sh` with JDK 25+ alone, and installed services
must execute npm/Java directly without calling the WIZ Spring CLI at runtime.

Do not rely on archived import material under `replaced-originals/`. It is
retained only so that imported user content can be reviewed without data loss.
