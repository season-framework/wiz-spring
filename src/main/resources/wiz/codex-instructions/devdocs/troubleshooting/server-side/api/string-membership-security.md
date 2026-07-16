# String Membership Security

증상: 권한 문자열 비교가 과도하게 허용된다.

원칙:

- role은 exact match로 비교한다.
- 여러 role은 `Set<String>`으로 관리한다.
- `"admin,user".contains(role)` 같은 방식은 사용하지 않는다.

```java
Set<String> allowed = Set.of("admin", "manager");
if (!allowed.contains(String.valueOf(wiz.session().get("role", "")))) {
    return wiz.response().status(401, Map.of("error", "forbidden"));
}
```
