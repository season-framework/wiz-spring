#!/usr/bin/env bash
set -Eeuo pipefail

repository_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
verification_root=$(mktemp -d "${TMPDIR:-/tmp}/wiz-template-verification.XXXXXX")

cleanup() {
    if [[ "${WIZ_KEEP_TEMPLATE_VERIFICATION:-0}" == "1" ]]; then
        printf 'Verification projects retained at %s\n' "$verification_root"
        return
    fi
    rm -rf -- "$verification_root"
}
trap cleanup EXIT

cd "$repository_root"
scripts/verify-documentation.sh
if [[ -n "${WIZ_SPRING_JAR:-}" ]]; then
    generator_jar=$(realpath "$WIZ_SPRING_JAR")
else
    ./mvnw --batch-mode --no-transfer-progress clean package
    generator_jar=$(find "$repository_root/target" -maxdepth 1 -type f \
        -name 'wiz-spring-*.jar' ! -name 'original-*' -print -quit)
fi

if [[ -z "${generator_jar:-}" || ! -s "$generator_jar" ]]; then
    printf 'A built WIZ Spring generator JAR was not found.\n' >&2
    exit 1
fi

has_npm_script() {
    node -e 'const pkg = require("./package.json"); process.exit(pkg.scripts?.[process.argv[1]] ? 0 : 1)' "$1"
}

verify_npm_install() {
    local log_file=$1
    npm ci --no-fund 2>&1 | tee "$log_file"
    if grep -Eiq '^npm warn (deprecated|EBADENGINE|ERESOLVE|install-scripts)' "$log_file"; then
        printf 'npm emitted a dependency warning; see %s\n' "$log_file" >&2
        return 1
    fi
}

templates=(angular-wiz angular react html jsp)
for template in "${templates[@]}"; do
    project="$verification_root/$template"
    package_suffix=${template//-/_}
    printf '\n==> Generating and verifying %s\n' "$template"
    java -jar "$generator_jar" create "$project" \
        --package "com.example.verification.$package_suffix" \
        --template "$template"

    (
        cd "$project"
        verify_npm_install "$verification_root/npm-ci-$template.log"
        npm audit --audit-level=low
        if has_npm_script test:wizbuild; then npm run test:wizbuild; fi
        if has_npm_script frontend:test; then npm run frontend:test; fi
        npm run frontend:build
        npm run backend:build
        npm run build
        npm run bundle
        (cd bundle && sha256sum -c SHA256SUMS)
        node -e '
          const fs = require("node:fs");
          const manifest = JSON.parse(fs.readFileSync("bundle/manifest.json", "utf8"));
          const expected = process.argv[1];
          const artifact = expected === "jsp" ? "war" : "jar";
          const publicEntry = expected === "jsp" ? "bundle/public/js/shell.js" : "bundle/public/index.html";
          if (manifest.template !== expected || manifest.artifact.type !== artifact) process.exit(1);
          if (!fs.existsSync(`bundle/app/application.${artifact}`)) process.exit(1);
          if (!fs.existsSync(publicEntry)) process.exit(1);
        ' "$template"
        if [[ "$template" == "jsp" ]]; then
            jar tf bundle/app/application.war | grep -qx 'WEB-INF/jsp/dashboard.jsp'
        fi
    )
done

printf '\nAll five generated templates passed install, audit, test, build, and bundle verification.\n'
