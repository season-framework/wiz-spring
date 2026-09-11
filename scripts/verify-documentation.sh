#!/usr/bin/env bash
set -Eeuo pipefail

repository_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repository_root"

require_text() {
    local file=$1
    local expected=$2
    if ! grep -Fq -- "$expected" "$file"; then
        printf 'Documentation policy mismatch: %s does not contain %s\n' "$file" "$expected" >&2
        return 1
    fi
}

reject_text() {
    local file=$1
    local retired=$2
    if grep -Fq -- "$retired" "$file"; then
        printf 'Retired documentation contract found: %s contains %s\n' "$file" "$retired" >&2
        return 1
    fi
}

current_docs=(
    README.md
    README.ko.md
    docs/project-generation.md
    docs/project-generation.ko.md
    docs/ai-instructions.md
    docs/ai-instructions.ko.md
    src/main/resources/wiz/templates/project-common/README.md
    src/main/resources/wiz/templates/project-common/AGENTS.md
    src/main/resources/wiz/templates/project-common/.github/copilot-instructions.md
    src/main/resources/wiz/templates/project-common/docs/ai/backend-spring.md
    src/main/resources/wiz/templates/project-common/docs/ai/deployment.md
)

for file in "${current_docs[@]}"; do
    require_text "$file" '1.2.2'
    reject_text "$file" 'Java 21'
    reject_text "$file" 'JDK 21'
    reject_text "$file" 'Spring Boot 4.0.6'
    reject_text "$file" 'wiz-spring-1.1.0.jar'
    reject_text "$file" 'wiz-spring-1.1.1.jar'
done

for file in README.md README.ko.md docs/project-generation.md docs/project-generation.ko.md; do
    require_text "$file" 'Spring Boot `4.1.1`'
    require_text "$file" 'Spring Framework `7.0.9`'
    require_text "$file" 'springdoc `3.1.0`'
    require_text "$file" 'Maven Wrapper `3.9.15`'
    require_text "$file" 'Angular `22.1.4`'
    require_text "$file" 'React `19.2.8`'
done

for file in \
    docs/ai-instructions.md \
    docs/ai-instructions.ko.md \
    src/main/resources/wiz/templates/project-common/README.md \
    src/main/resources/wiz/templates/project-common/AGENTS.md \
    src/main/resources/wiz/templates/project-common/.github/copilot-instructions.md \
    src/main/resources/wiz/templates/project-common/docs/ai/backend-spring.md; do
    require_text "$file" 'Spring Framework `7.0.9`'
done

require_text pom.xml '<version>1.2.2</version>'
require_text pom.xml '<maven.compiler.release>25</maven.compiler.release>'
require_text src/main/resources/wiz/templates/project-angular/pom.xml '<version>4.1.1</version>'
require_text src/main/resources/wiz/templates/project-angular/pom.xml '<java.version>25</java.version>'
require_text src/main/resources/wiz/templates/project-angular/package.json '"@angular/core": "22.1.4"'
require_text src/main/resources/wiz/templates/project-react/package.json '"react": "19.2.8"'
require_text src/main/resources/wiz/templates/project-common/.env 'SERVER_PORT=8080'
require_text helper/.env 'WIZ_HELPER_PORT=8080'
require_text helper/docs/operations.md 'checked-in [`helper/.env`](../.env)'
require_text helper/docs/operations.ko.md '기본값이 포함된 [`helper/.env`](../.env)'
require_text helper/internal/generator/generator.go 'const Version = "1.2.2"'
require_text helper/internal/httpapi/openapi.yaml 'version: 1.2.2'
require_text release-log/1.2.2.md '# WIZ Spring 1.2.2'
require_text release-log/README.md '[`1.2.2`](1.2.2.md)'

deployment_docs=(
    docs/build-and-deployment.md
    docs/build-and-deployment.ko.md
    src/main/resources/wiz/templates/project-common/README.md
    src/main/resources/wiz/templates/project-common/AGENTS.md
    src/main/resources/wiz/templates/project-common/deploy/README.md
    src/main/resources/wiz/templates/project-common/docs/ai/deployment.md
)

for file in "${deployment_docs[@]}"; do
    require_text "$file" 'application.jar'
    require_text "$file" 'docker-compose.yaml'
    reject_text "$file" './run.sh'
    reject_text "$file" 'prod,bundle'
    reject_text "$file" 'docker compose --profile nginx'
    reject_text "$file" 'docker compose --profile apache2'
done

for proxy_example in \
    src/main/resources/wiz/templates/project-common/deploy/nginx/default.conf.example \
    src/main/resources/wiz/templates/project-common/deploy/apache2/wiz.conf.example \
    src/main/resources/wiz/templates/project-jsp/deploy/nginx/default.conf.example \
    src/main/resources/wiz/templates/project-jsp/deploy/apache2/wiz.conf.example; do
    if [[ ! -f "$proxy_example" ]]; then
        printf 'Missing reverse-proxy example: %s\n' "$proxy_example" >&2
        exit 1
    fi
done

for retired_env_example in \
    src/main/resources/wiz/templates/project-common/.env.example \
    src/main/resources/wiz/templates/project-common/deploy/.env.example \
    helper/.env.example; do
    if [[ -e "$retired_env_example" ]]; then
        printf 'Retired environment example still exists: %s\n' "$retired_env_example" >&2
        exit 1
    fi
done

frontend_guides=(
    src/main/resources/wiz/templates/project-angular-wiz/docs/ai/frontend.md
    src/main/resources/wiz/templates/project-angular/docs/ai/frontend.md
    src/main/resources/wiz/templates/project-react/docs/ai/frontend.md
    src/main/resources/wiz/templates/project-html/docs/ai/frontend.md
    src/main/resources/wiz/templates/project-jsp/docs/ai/frontend.md
)

for file in "${frontend_guides[@]}"; do
    require_text "$file" 'WIZ Spring `1.2.2`'
    reject_text "$file" 'Angular 21'
done

printf 'Documentation and executable version policies are aligned with WIZ Spring 1.2.2.\n'
