#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

default_version() {
    sed -n '/<artifactId>wiz-spring<\/artifactId>/,/<version>/{s:.*<version>\([^<]*\)</version>.*:\1:p;}' \
        "$SCRIPT_DIR/pom.xml" | head -n 1
}

IMAGE="${IMAGE:-registry.nanoha.kr/kwon3286/wiz-spring}"
VERSION="${VERSION:-$(default_version)}"
PLATFORM="${PLATFORM:-linux/amd64}"
CONTAINER_NAME="${CONTAINER_NAME:-wiz-spring-test}"
HOST_HTTP_PORT="${HOST_HTTP_PORT:-3334}"
HOST_SSH_PORT="${HOST_SSH_PORT:-2223}"
CONTAINER_HTTP_PORT="${CONTAINER_HTTP_PORT:-3000}"

args=(
    --detach
    --init
    --platform "$PLATFORM"
    --publish "$HOST_HTTP_PORT:$CONTAINER_HTTP_PORT"
    --publish "$HOST_SSH_PORT:22"
    --env "WIZ_PORT=$CONTAINER_HTTP_PORT"
    --env "WIZ_ENABLE_SSH=${WIZ_ENABLE_SSH:-true}"
    --name "$CONTAINER_NAME"
)

if [[ -n "${SSH_PASSWORD:-}" ]]; then
    args+=(--env SSH_PASSWORD)
fi

container_id="$(docker run "${args[@]}" "$IMAGE:$VERSION")"
printf 'Started %s (%s)\n' "$CONTAINER_NAME" "$container_id"
printf 'HTTP: http://127.0.0.1:%s\n' "$HOST_HTTP_PORT"
printf 'SSH: ssh root@127.0.0.1 -p %s\n' "$HOST_SSH_PORT"
