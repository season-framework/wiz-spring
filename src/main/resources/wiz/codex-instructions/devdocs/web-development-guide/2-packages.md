# Portal Package Guide

Portal package는 재사용 가능한 app, route, model, libs, assets 묶음이다.

```text
src/portal/season/
  portal.json
  app/
  controller/
  model/
  route/
  libs/
  assets/
  styles/
```

`portal.json`의 `use_app`, `use_route`, `use_model`, `use_libs`, `use_assets` flag는 build 대상 포함 여부를 결정한다.

현재 CLI에는 package/app/route scaffold 하위 명령이 없다. package source는 아래 구조를 직접 만들거나, MCP를 사용하는 환경에서는 `wiz_package_create`, `wiz_source_create_app` 계열 도구를 사용한다.

`wiz-spring build --root "$workspace" --clean`으로 package source가 build 대상에 포함되는지 검증한다.
