# Java Source Order

Spring port는 동적 script 실행 순서에 의존하지 않는다. compile 가능한 Java class와 method signature가 기준이다.

확인:

- public class 이름과 파일의 의도된 handler 이름이 일치하는지 확인한다.
- package 선언이 없으면 build rewrite convention을 따른다.
- handler metadata를 명시했다면 FQCN이 실제 class와 맞아야 한다.
