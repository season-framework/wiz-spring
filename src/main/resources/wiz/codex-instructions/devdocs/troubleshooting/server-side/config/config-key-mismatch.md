# Config Key Mismatch

증상: 설정값이 기본값으로만 읽힌다.

확인:

- Spring server 설정은 `config/application.yml` 계층에 둔다.
- namespace 설정은 `config/{name}.yml`로 분리할 수 있다.
- 공통값은 `application.yml`, profile별 override는 `application-<profile>.yml`에 둔다.
- `wiz-spring run`의 기본은 `dev`, 인자 없는 standalone jar의 기본은 `prod`다. 실행 중인 값을 바꾸려면 `wiz-spring run --profile <name>`으로 명시한다.
- `application*.example.yml`은 runtime이 읽지 않는다. clone 후 선택한 example을 `.example` 없는 실제 파일명으로 복사했는지 확인한다.
- CLI `--host`, `--port`, `--profile`이 마지막 우선순위다.
