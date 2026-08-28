#!/bin/sh
set -eu

helper_url="${WIZ_HELPER_URL:-http://127.0.0.1:8080}"
e2e_directory="$(mktemp -d /tmp/wiz-spring-helper-e2e.XXXXXX)"
trap 'rm -rf "$e2e_directory"' EXIT HUP INT TERM

curl --fail --silent --show-error --retry 20 --retry-all-errors --retry-connrefused --retry-delay 1 \
  "$helper_url/readyz" > "$e2e_directory/ready.json"

for template in angular-wiz angular react html jsp; do
  case "$template" in
    angular-wiz) package_suffix="angularwiz" ;;
    *) package_suffix="$template" ;;
  esac

  project_name="helper-$template"
  package_name="kr.nanoha.helper.$package_suffix"
  archive="$e2e_directory/$project_name.zip"
  headers="$e2e_directory/$project_name.headers"

  case "$template" in
    angular)
      curl --fail-with-body --silent --show-error \
        -X POST "$helper_url/api/v1/projects" \
        -H 'Content-Type: application/x-www-form-urlencoded' \
        -D "$headers" \
        --data-urlencode "projectName=$project_name" \
        --data-urlencode "packageName=$package_name" \
        --data-urlencode "template=$template" \
        -o "$archive"
      ;;
    react)
      curl --fail-with-body --silent --show-error \
        -X POST "$helper_url/api/v1/projects?projectName=$project_name&packageName=$package_name&template=$template" \
        -D "$headers" \
        -o "$archive"
      ;;
    *)
      curl --fail-with-body --silent --show-error \
        -X POST "$helper_url/api/v1/projects" \
        -H 'Content-Type: application/json' \
        -D "$headers" \
        -d "{\"projectName\":\"$project_name\",\"packageName\":\"$package_name\",\"template\":\"$template\"}" \
        -o "$archive"
      ;;
  esac

  grep -Fqi "content-disposition: attachment; filename=\"$project_name.zip\"" "$headers"
  grep -Fqi "x-project-template: $template" "$headers"
  grep -Fqi "x-base-template: $template" "$headers"
  unzip -tqq "$archive"

  entries="$(unzip -Z1 "$archive")"
  printf '%s\n' "$entries" | awk -v prefix="$project_name/" '
    index($0, prefix) != 1 { exit 1 }
    END { if (NR == 0) exit 1 }
  '
  if printf '%s\n' "$entries" | grep -Eq '(^|/)\.wiz(/|$)'; then
    echo "Unexpected .wiz entry in $archive" >&2
    exit 1
  fi

  unzip -p "$archive" "$project_name/package.json" | grep -Fq "\"frontend\": \"$template\""
  java_path="$(printf '%s' "$package_name" | tr '.' '/')"
  unzip -p "$archive" "$project_name/src/main/java/$java_path/Application.java" \
    | grep -Fq "package $package_name;"
  unzip -Z -l "$archive" "$project_name/mvnw" | grep -Eq '^-rwx'

  echo "verified $template: $(wc -c < "$archive") bytes"
done

problem_status="$(curl --silent --show-error \
  -X POST "$helper_url/api/v1/projects" \
  -H 'Content-Type: application/json' \
  -d '{"projectName":"invalid","packageName":"kr.nanoha.invalid-package"}' \
  -o "$e2e_directory/problem.json" \
  -w '%{http_code}')"
test "$problem_status" = "422"
grep -Fq '"code":"validation_failed"' "$e2e_directory/problem.json"

echo "all helper end-to-end checks passed"
