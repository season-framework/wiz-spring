#!/bin/sh
set -eu

script_directory="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
helper_directory="$(CDPATH= cd -- "$script_directory/.." && pwd)"
repository_directory="$(CDPATH= cd -- "$helper_directory/.." && pwd)"
jar_path="${WIZ_SPRING_JAR:-$repository_directory/target/wiz-spring-1.1.0.jar}"
image="${WIZ_HELPER_CUSTOM_TEST_IMAGE:-wiz-spring-helper:1.1.0-custom-e2e}"
container_name="wiz-spring-helper-custom-e2e-$$"

if [ ! -f "$jar_path" ]; then
  echo "Missing WIZ Spring JAR: $jar_path" >&2
  exit 1
fi
if [ -n "$(docker ps -aq --filter "name=^/$container_name$")" ]; then
  echo "Unexpected existing test container: $container_name" >&2
  exit 1
fi

cleanup() {
  docker rm -f "$container_name" >/dev/null 2>&1 || true
}
trap cleanup EXIT HUP INT TERM

docker build \
  --build-arg WIZ_HELPER_TEMPLATE_FILE=registry.example.json \
  -f "$helper_directory/Dockerfile" \
  -t "$image" \
  "$helper_directory"

assets="$(docker run --rm --entrypoint /usr/bin/find "$image" \
  /opt/wiz-helper/templates -type f -printf '%P\n' | sort)"
test "$(printf '%s\n' "$assets" | wc -l)" -eq 3
printf '%s\n' "$assets" | grep -Fxq 'registry.json'
printf '%s\n' "$assets" | grep -Fxq 'examples/company-react/overlay/README.md'
printf '%s\n' "$assets" | grep -Fxq 'examples/company-react/overlay/docs/ai/company-template.md'
if printf '%s\n' "$assets" | grep -Eq 'registry\.example\.json|unused'; then
  echo "The final image contains an unselected template asset" >&2
  exit 1
fi
docker run --rm --entrypoint /usr/bin/test "$image" ! -e /opt/wiz-source/wiz-spring.jar

docker run -d \
  --name "$container_name" \
  --init \
  --read-only \
  --tmpfs /tmp:size=64m,mode=1777 \
  --tmpfs /work:size=512m,uid=10001,gid=10001,mode=0700 \
  --cap-drop ALL \
  --cap-add DAC_READ_SEARCH \
  --cap-add SETUID \
  --cap-add SETGID \
  --security-opt no-new-privileges:true \
  --pids-limit 256 \
  --memory 1g \
  --cpus 2 \
  -e WIZ_HELPER_TEMPLATE_REGISTRY=/tmp/runtime-override-must-be-ignored.json \
  -v "$jar_path:/opt/wiz-source/wiz-spring.jar:ro" \
  -p 127.0.0.1::8080 \
  "$image" >/dev/null

published="$(docker port "$container_name" 8080/tcp | head -n 1)"
case "$published" in
  127.0.0.1:*) ;;
  *) echo "Unexpected published helper address: $published" >&2; exit 1 ;;
esac

WIZ_HELPER_URL="http://$published" "$script_directory/e2e-custom.sh"
echo "custom helper image contract checks passed"
