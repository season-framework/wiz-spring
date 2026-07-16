# Java Object Conventions

WIZ Spring API는 Java object를 JSON envelope로 직렬화한다.

권장 반환 타입:

- `Map<String, Object>`: 동적 response data
- `List<Map<String, Object>>`: 목록
- record/class DTO: 구조가 고정된 API
- `WizResult`: status, header, redirect, download가 필요한 응답

Plain object를 반환하면 runtime이 HTTP 200 envelope로 감싼다. 오류 status가 필요한 경우 반드시 `WizResult`를 반환한다.
