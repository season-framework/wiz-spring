#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
    printf 'Usage: %s <image> <expected-wiz-version> <expected-template>\n' "$0" >&2
    exit 64
fi

image="$1"
expected_version="$2"
expected_template="$3"
expect_codex="${EXPECT_CODEX:-true}"

declared_volumes="$(docker image inspect --format '{{json .Config.Volumes}}' "$image")"
if [[ "$declared_volumes" != null && "$declared_volumes" != '{}' ]]; then
    printf 'Image must not declare volumes: %s\n' "$declared_volumes" >&2
    exit 1
fi

actual_template="$(docker image inspect \
    --format '{{index .Config.Labels "io.wiz.spring.frontend-template"}}' "$image")"
if [[ "$actual_template" != "$expected_template" ]]; then
    printf 'Unexpected image template label: %s\n' "$actual_template" >&2
    exit 1
fi

docker run --rm \
    --network none \
    --env "EXPECTED_WIZ_VERSION=$expected_version" \
    --env "EXPECTED_TEMPLATE=$expected_template" \
    --env "EXPECT_CODEX=$expect_codex" \
    --entrypoint /bin/bash \
    "$image" \
    -lc '
        set -euo pipefail
        test "$APP_ROOT" = /opt/app
        test -s "$APP_ROOT/.env"
        test -s "$APP_ROOT/package.json"
        test -d "$APP_ROOT/src/main/java"
        test -d /root/.m2/repository
        test "$(wiz-spring --version)" = "wiz-spring $EXPECTED_WIZ_VERSION"
        test "$(java -XshowSettings:properties -version 2>&1 | sed -n "s/^[[:space:]]*java\.specification\.version = //p" | head -n 1)" -ge 25
        test -x "$JAVA_HOME/bin/javac"
        node -e '\''
            const fs = require("node:fs");
            const p = JSON.parse(fs.readFileSync("/opt/app/package.json", "utf8"));
            if (p.version !== process.env.EXPECTED_WIZ_VERSION) process.exit(1);
            if (p.wiz?.frontend !== process.env.EXPECTED_TEMPLATE) process.exit(1);
        '\''
        npm --version
        ./mvnw --version >/dev/null
        if [[ "$EXPECT_CODEX" == true ]]; then codex --version; fi
        ssh-keygen -A >/dev/null
        /usr/sbin/sshd -t
        /usr/sbin/sshd -T | grep -Fx "permitrootlogin yes"
        /usr/sbin/sshd -T | grep -Fx "passwordauthentication yes"
        /usr/sbin/sshd -T | grep -Fx "pubkeyauthentication no"
    '

container_id="$(docker run --detach \
    --env WIZ_ENABLE_SSH=false \
    --env SERVER_PORT=18080 \
    "$image")"

cleanup() {
    docker rm --force "$container_id" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [[ "$(docker inspect --format '{{json .Mounts}}' "$container_id")" != '[]' ]]; then
    printf 'Verification container unexpectedly has a mount\n' >&2
    exit 1
fi

ready=false
for _ in $(seq 1 90); do
    state="$(docker inspect --format '{{.State.Status}}' "$container_id")"
    if [[ "$state" != running ]]; then
        printf 'Container exited before becoming ready\n' >&2
        docker logs "$container_id" >&2
        exit 1
    fi
    if docker exec "$container_id" \
        curl --fail --silent --show-error \
        http://127.0.0.1:18080/actuator/health >/dev/null 2>&1; then
        ready=true
        break
    fi
    sleep 2
done

if [[ "$ready" != true ]]; then
    printf 'Container did not become ready: %s\n' "$image" >&2
    docker logs "$container_id" >&2
    exit 1
fi

printf 'Verified no-mount development image: %s\n' "$image"
