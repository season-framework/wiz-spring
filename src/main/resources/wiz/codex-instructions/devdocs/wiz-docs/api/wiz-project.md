# Wiz Workspace Context

`wiz.project()`는 현재 단일 workspace app context다. 이름은 기존 API 호환을 위해 남아 있지만 여러 project를 전환하지 않는다.

주요 root:

- `root()`: workspace root
- `sourceRoot()`: `src`
- `appRoot()`: `src/app`
- `routeRoot()`: `src/route`
- `configRoot()`: `config`
- `bundleRoot()`: `bundle`

파일 접근은 workspace root를 벗어나지 않도록 normalize와 safe path 정책을 함께 적용한다. public static 파일은 source가 아니라 bundle 기준으로 제공된다.
