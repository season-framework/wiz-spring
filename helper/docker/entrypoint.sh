#!/bin/sh
set -eu

source_jar="${WIZ_SPRING_SOURCE_JAR:-/opt/wiz-source/wiz-spring.jar}"
runtime_jar="/tmp/wiz-spring-1.0.0.jar"

if [ ! -f "$source_jar" ]; then
  echo "WIZ Spring JAR is not mounted: $source_jar" >&2
  exit 1
fi

install -m 0444 "$source_jar" "$runtime_jar"
export WIZ_SPRING_JAR="$runtime_jar"
export WIZ_HELPER_TEMPLATE_REGISTRY="/opt/wiz-helper/templates/registry.json"

exec setpriv \
  --reuid=10001 \
  --regid=10001 \
  --clear-groups \
  --no-new-privs \
  /usr/local/bin/wiz-spring-helper
