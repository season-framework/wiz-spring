#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
    printf 'Usage: %s <image> [expected-wiz-version]\n' "$0" >&2
    exit 64
fi

image="$1"
expected_wiz_version="${2:-}"

verify_runtime='
set -eu

if [ -z "${JAVA_HOME:-}" ]; then
    printf "JAVA_HOME is not set\n" >&2
    exit 1
fi
if [ ! -x "$JAVA_HOME/bin/java" ]; then
    printf "Java executable not found below JAVA_HOME: %s\n" "$JAVA_HOME" >&2
    exit 1
fi
if [ ! -x "$JAVA_HOME/bin/javac" ]; then
    printf "Java compiler not found below JAVA_HOME: %s\n" "$JAVA_HOME" >&2
    exit 1
fi

java_path="$(command -v java || true)"
if [ -z "$java_path" ]; then
    printf "Java was not found on PATH\n" >&2
    exit 1
fi
if [ "$(readlink -f "$java_path")" != "$(readlink -f "$JAVA_HOME/bin/java")" ]; then
    printf "PATH Java does not match JAVA_HOME: %s != %s\n" \
        "$java_path" "$JAVA_HOME/bin/java" >&2
    exit 1
fi

java_specification_version="$(
    java -XshowSettings:properties -version 2>&1 |
        sed -n "s/^[[:space:]]*java\.specification\.version = //p" |
        head -n 1
)"
java_major_version="${java_specification_version%%.*}"
case "$java_major_version" in
    ""|*[!0-9]*)
        printf "Could not determine the Java major version: %s\n" \
            "$java_specification_version" >&2
        exit 1
        ;;
esac
if [ "$java_major_version" -lt 21 ]; then
    printf "Java 21 or newer is required, found: %s\n" \
        "$java_specification_version" >&2
    exit 1
fi

actual_wiz_version="$(wiz-spring --version)"
if [ -n "${EXPECTED_WIZ_VERSION:-}" ] &&
    [ "$actual_wiz_version" != "wiz-spring $EXPECTED_WIZ_VERSION" ]; then
    printf "Unexpected WIZ Spring version: %s\n" "$actual_wiz_version" >&2
    exit 1
fi
'

docker run --rm \
    --network none \
    --env "EXPECTED_WIZ_VERSION=$expected_wiz_version" \
    --entrypoint /bin/sh \
    "$image" \
    -c "$verify_runtime"

docker run --rm \
    --network none \
    --entrypoint /usr/bin/env \
    "$image" \
    -i \
    HOME=/root \
    USER=root \
    LOGNAME=root \
    SHELL=/bin/bash \
    "EXPECTED_WIZ_VERSION=$expected_wiz_version" \
    /bin/bash --login -c "$verify_runtime"

printf 'Verified Java 21+ and WIZ Spring in container and login shells: %s\n' "$image"
