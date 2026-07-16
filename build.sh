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
BUILD_TARGET="${1:-runtime}"

if [[ -z "$VERSION" ]]; then
    printf 'Failed to read the WIZ Spring version from pom.xml\n' >&2
    exit 1
fi

build_args=(
    --platform "$PLATFORM"
    --build-arg "DOCKER_PLATFORM=$PLATFORM"
    --build-arg "WIZ_VERSION=$VERSION"
)

for name in INSTALL_CODEX CODEX_VERSION WIZ_PACKAGE_ROOT NODE_IMAGE MAVEN_IMAGE; do
    if [[ -n "${!name:-}" ]]; then
        build_args+=(--build-arg "$name=${!name}")
    fi
done

build_runtime() {
    docker build "${build_args[@]}" --target runtime -t "$IMAGE:$VERSION" "$SCRIPT_DIR"
}

build_bind() {
    docker build "${build_args[@]}" --target runtime-bind -t "$IMAGE:$VERSION-bind" "$SCRIPT_DIR"
}

case "$BUILD_TARGET" in
    all)
        build_runtime
        build_bind
        ;;
    runtime)
        build_runtime
        ;;
    bind)
        build_bind
        ;;
    *)
        printf 'Usage: %s [all|runtime|bind]\n' "$0" >&2
        exit 1
        ;;
esac
