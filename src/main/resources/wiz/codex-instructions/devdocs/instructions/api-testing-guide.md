# API Testing Guide

Spring WIZ API는 HTTP endpoint와 Servlet session cookie로 테스트한다. 별도 서명 쿠키를 직접 만들 필요가 없다.
기본 `wiz-spring run`은 dev profile이라 로컬 HTTP cookie 테스트가 가능하다. prod profile의 session cookie는 `Secure=true`이므로 HTTPS endpoint에서 테스트한다.

## 서버 실행

```bash
jar=/root/workspace/wiz-java/wiz-spring/target/wiz-spring-0.2.8.jar
workspace=/tmp/wiz-spring-demo

java -jar "$jar" run --root "$workspace" --port 3000
```

## 로그인 후 API 호출

```bash
curl -i -c /tmp/wiz-cookie.txt \
  -X POST http://127.0.0.1:3000/wiz/api/page.access/login \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'email=admin@example.com&password=admin1234'

curl -i -b /tmp/wiz-cookie.txt \
  -X POST http://127.0.0.1:3000/wiz/api/page.dashboard/overview
```

## JSON body

```bash
curl -i -b /tmp/wiz-cookie.txt \
  -X POST http://127.0.0.1:3000/wiz/api/page.members/invite \
  -H 'Content-Type: application/json' \
  -d '{"email":"new@example.com","name":"New User","role":"user"}'
```

Top-level JSON key는 `wiz.request().query()`에서도 읽힌다. 같은 key가 query string에 있으면 query string 값이 우선한다.

## Auth route

```bash
curl -i -b /tmp/wiz-cookie.txt http://127.0.0.1:3000/auth/check
curl -i -b /tmp/wiz-cookie.txt 'http://127.0.0.1:3000/auth/logout?returnTo=/'
```

`/auth/check`는 로그인 여부와 무관하게 HTTP 200을 반환하고 body의 `data.status`로 상태를 표현한다.
