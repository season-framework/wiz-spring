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
    require_text "$file" '1.1.1'
    reject_text "$file" 'Java 21'
    reject_text "$file" 'JDK 21'
    reject_text "$file" 'Spring Boot 4.0.6'
    reject_text "$file" 'wiz-spring-1.1.0.jar'
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

require_text pom.xml '<version>1.1.1</version>'
require_text pom.xml '<maven.compiler.release>25</maven.compiler.release>'
require_text src/main/resources/wiz/templates/project-angular/pom.xml '<version>4.1.1</version>'
require_text src/main/resources/wiz/templates/project-angular/pom.xml '<java.version>25</java.version>'
require_text src/main/resources/wiz/templates/project-angular/package.json '"@angular/core": "22.1.4"'
require_text src/main/resources/wiz/templates/project-react/package.json '"react": "19.2.8"'

frontend_guides=(
    src/main/resources/wiz/templates/project-angular-wiz/docs/ai/frontend.md
    src/main/resources/wiz/templates/project-angular/docs/ai/frontend.md
    src/main/resources/wiz/templates/project-react/docs/ai/frontend.md
    src/main/resources/wiz/templates/project-html/docs/ai/frontend.md
    src/main/resources/wiz/templates/project-jsp/docs/ai/frontend.md
)

for file in "${frontend_guides[@]}"; do
    require_text "$file" 'WIZ Spring `1.1.1`'
    reject_text "$file" 'Angular 21'
done

printf 'Documentation and executable version policies are aligned with WIZ Spring 1.1.1.\n'
