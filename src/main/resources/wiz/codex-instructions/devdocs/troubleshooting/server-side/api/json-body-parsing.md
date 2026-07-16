# JSON Body Parsing

증상: JSON body로 보낸 값이 API에서 비어 보인다.

확인:

- `Content-Type: application/json`을 보냈는지 확인한다.
- top-level object만 `wiz.request().query()`에 병합된다.
- 중첩 object나 array는 `wiz.request().json("key")`로 읽는다.
- 같은 key가 query string에 있으면 query string 값이 우선한다.

예:

```java
Map<String, Object> json = wiz.request().json();
Object filters = wiz.request().json("filters").orElse(Map.of());
```
