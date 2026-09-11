#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"

default_version() {
    sed -n '/<artifactId>wiz-spring<\/artifactId>/,/<version>/{s:.*<version>\([^<]*\)</version>.*:\1:p;}' \
        "$PROJECT_DIR/pom.xml" | head -n 1
}

usage() {
    printf 'Usage: %s [all|angular-wiz|angular|react|html|jsp] [--push|--no-push]\n' "$0"
}

IMAGE="${IMAGE:-registry.nanoha.kr/kwon3286/wiz-spring}"
VERSION="${VERSION:-$(default_version)}"
PLATFORM="${PLATFORM:-linux/amd64}"
PUSH="${PUSH:-false}"
selection=all

while [[ $# -gt 0 ]]; do
    case "$1" in
        all|angular-wiz|angular|react|html|jsp)
            selection="$1"
            ;;
        --push)
            PUSH=true
            ;;
        --no-push)
            PUSH=false
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            usage >&2
            exit 64
            ;;
    esac
    shift
done

if [[ -z "$VERSION" ]]; then
    printf 'Failed to read the WIZ Spring version from pom.xml\n' >&2
    exit 1
fi

case "$PUSH" in
    true|false) ;;
    *) printf 'PUSH must be true or false\n' >&2; exit 1 ;;
esac

if [[ "$selection" == all ]]; then
    templates=(angular-wiz angular react html jsp)
else
    templates=("$selection")
fi

build_args=(
    --platform "$PLATFORM"
    --file "$PROJECT_DIR/Dockerfile.dev"
    --build-arg "DOCKER_PLATFORM=$PLATFORM"
    --build-arg "WIZ_VERSION=$VERSION"
)

for name in INSTALL_CODEX CODEX_VERSION WIZ_PACKAGE_ROOT NODE_IMAGE MAVEN_IMAGE; do
    if [[ -n "${!name:-}" ]]; then
        build_args+=(--build-arg "$name=${!name}")
    fi
done

tags=()
for template in "${templates[@]}"; do
    tag="$IMAGE:$VERSION-$template"
    printf 'Building %s\n' "$tag"
    docker build \
        "${build_args[@]}" \
        --build-arg "WIZ_TEMPLATE=$template" \
        --tag "$tag" \
        "$PROJECT_DIR"
    EXPECT_CODEX="${INSTALL_CODEX:-true}" \
        "$SCRIPT_DIR/verify-dev-image.sh" "$tag" "$VERSION" "$template"
    tags+=("$tag")
done

if [[ " ${templates[*]} " == *' angular-wiz '* ]]; then
    default_tag="$IMAGE:$VERSION"
    docker tag "$IMAGE:$VERSION-angular-wiz" "$default_tag"
    tags+=("$default_tag")
fi

if [[ "$PUSH" == true ]]; then
    for tag in "${tags[@]}"; do
        printf 'Pushing %s\n' "$tag"
        docker push "$tag"
    done
fi

printf 'Completed images:\n'
printf '  %s\n' "${tags[@]}"
