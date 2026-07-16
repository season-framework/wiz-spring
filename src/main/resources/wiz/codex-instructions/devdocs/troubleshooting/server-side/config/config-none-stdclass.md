# Missing Config Namespace

증상: namespace config가 비어 있다.

확인:

- `config/{namespace}.yml` 파일명을 확인한다.
- build 후 bundle config에 복사됐는지 확인한다.
- optional config는 기본값을 코드에 둔다.

```java
String url = wiz.config().namespace("database").string("url", "");
```
