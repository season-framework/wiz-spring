#!/bin/sh
set -eu

helper_url="${WIZ_HELPER_URL:-http://127.0.0.1:8080}"
e2e_directory="$(mktemp -d /tmp/wiz-spring-helper-custom-e2e.XXXXXX)"
trap 'rm -rf "$e2e_directory"' EXIT HUP INT TERM

curl --fail --silent --show-error --retry 30 --retry-all-errors --retry-connrefused --retry-delay 1 \
  "$helper_url/api/v1/templates" > "$e2e_directory/templates.json"

grep -Fq '"default":"company-react"' "$e2e_directory/templates.json"
grep -Fq '"id":"company-react"' "$e2e_directory/templates.json"
grep -Fq '"base":"react"' "$e2e_directory/templates.json"
if grep -Fq '"id":"jsp"' "$e2e_directory/templates.json"; then
  echo "jsp must not be exposed by the custom registry" >&2
  exit 1
fi

project_name="helper-company-react"
package_name="kr.nanoha.helper.companyreact"
archive="$e2e_directory/$project_name.zip"
headers="$e2e_directory/$project_name.headers"

curl --fail-with-body --silent --show-error \
  -X POST "$helper_url/api/v1/projects" \
  -H 'Content-Type: application/json' \
  -D "$headers" \
  -d "{\"projectName\":\"$project_name\",\"packageName\":\"$package_name\"}" \
  -o "$archive"

grep -Fqi 'x-project-template: company-react' "$headers"
grep -Fqi 'x-base-template: react' "$headers"
unzip -tqq "$archive"
entries="$(unzip -Z1 "$archive")"
if printf '%s\n' "$entries" | grep -Eq '(^|/)\.wiz(/|$)'; then
  echo "Unexpected .wiz entry in custom archive" >&2
  exit 1
fi

unzip -p "$archive" "$project_name/README.md" | grep -Fq "# $project_name"
unzip -p "$archive" "$project_name/README.md" | grep -Fq 'company-react'
unzip -p "$archive" "$project_name/docs/ai/company-template.md" | grep -Fq '`react` build contract'
unzip -p "$archive" "$project_name/package.json" | grep -Fq '"frontend": "react"'
if unzip -p "$archive" "$project_name/package.json" | grep -Fq '@season-framework/wiz-frontend'; then
  echo "Unexpected external WIZ frontend dependency" >&2
  exit 1
fi

status="$(curl --silent --show-error \
  -X POST "$helper_url/api/v1/projects" \
  -H 'Content-Type: application/json' \
  -d '{"projectName":"helper-jsp","packageName":"kr.nanoha.helper.jsp","template":"jsp"}' \
  -o "$e2e_directory/jsp-problem.json" \
  -w '%{http_code}')"
test "$status" = "422"
grep -Fq '"code":"validation_failed"' "$e2e_directory/jsp-problem.json"

echo "custom helper end-to-end checks passed"
