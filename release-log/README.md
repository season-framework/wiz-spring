# WIZ Spring Release Log

이 디렉토리는 WIZ Spring 포팅 작업의 릴리즈 로그를 버전 태그 단위로 정리한다.

| 버전 | 상태 | 기준 |
|------|------|------|
| [`0.0.1`](0.0.1.md) | tagged | 최초 Spring Boot 포팅 태그 |
| [`0.0.2`](0.0.2.md) | tagged | CLI parity, project-local runtime boundary, embedded Java sample project |
| [`0.0.3`](0.0.3.md) | tagged | wiz-spring CLI rename, standalone project jar packaging |
| [`0.0.4`](0.0.4.md) | tagged | runtime config hardening, project runtime cache, supply-chain manifests |
| [`0.0.5`](0.0.5.md) | tagged | service command parity, port/config resolution, service script hardening |
| [`0.0.6`](0.0.6.md) | tagged | Java API rebuild correctness, run log stdout capture, normal build npm reuse |
| [`0.0.7`](0.0.7.md) | tagged | project warmup hook, first-login latency reduction, sample seed preloading |
| [`0.1.0`](0.1.0.md) | tagged | app-oriented CLI, native WebSocket default, project observability, Angular platformBrowser smoke |
| [`0.2.0`](0.2.0.md) | tagged | Spring/Maven-shaped build output, hidden WIZ staging, generated package modernization |
| [`0.2.1`](0.2.1.md) | tagged | CLI workspace root auto-detection, root validation, runServer overload fix |
| [`0.2.2`](0.2.2.md) | tagged | service uninstall stop/disable/delete/daemon-reload sequence |
| [`0.2.3`](0.2.3.md) | tagged | Docker development environment, initial package selection, clean sample devlog |
| [`0.2.4`](0.2.4.md) | tagged | profile config Git policy, Servlet session cookie hardening, workspace metadata |
| [`0.2.5`](0.2.5.md) | tagged | repeatable package-root changes with automatic clean rebuild |
| [`0.2.6`](0.2.6.md) | tagged | automatic Codex setup on create, embedded instructions, and guided shell completion |
| [`0.2.7`](0.2.7.md) | tagged | bounded run logging, safe runtime reload, strict CLI preflight, deterministic deployment, OpenAPI/Swagger UI |

릴리즈를 실제로 배포할 때는 `pom.xml`의 `<version>`과 Git tag, GitHub/GitLab release note가 이 로그와 일치해야 한다.
