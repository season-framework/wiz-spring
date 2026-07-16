# syntax=docker/dockerfile:1.7

ARG DOCKER_PLATFORM=linux/amd64
ARG NODE_IMAGE=node:22-bookworm-slim
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21-noble

FROM --platform=${DOCKER_PLATFORM} ${NODE_IMAGE} AS node

FROM --platform=${DOCKER_PLATFORM} ${MAVEN_IMAGE} AS builder

WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && \
    ./mvnw --batch-mode --no-transfer-progress -DskipTests dependency:go-offline

COPY src/ src/
RUN ./mvnw --batch-mode --no-transfer-progress -DskipTests clean package && \
    runtime_jar="$(find target -maxdepth 1 -type f -name 'wiz-spring-*.jar' ! -name '*.original' -print -quit)" && \
    test -n "$runtime_jar" && \
    install -D -m 0644 "$runtime_jar" /opt/wiz-spring/wiz-spring.jar

FROM --platform=${DOCKER_PLATFORM} ${MAVEN_IMAGE} AS runtime-tools

ARG DEBIAN_FRONTEND=noninteractive
ARG WIZ_VERSION=0.2.5
ARG WIZ_PACKAGE_ROOT=com.wiz.app
ARG INSTALL_CODEX=true
ARG CODEX_VERSION=latest

ENV TZ=Asia/Seoul \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    APP_DIR=app \
    APP_ROOT=/opt/app \
    DATA_DIR=/mnt/data \
    WIZ_HOME=/opt/wiz-spring \
    WIZ_RUNTIME_JAR=/opt/wiz-spring/wiz-spring.jar \
    WIZ_PACKAGE_ROOT=${WIZ_PACKAGE_ROOT} \
    WIZ_PORT=3000 \
    WIZ_ENABLE_SSH=true

LABEL org.opencontainers.image.title="WIZ Spring development environment" \
      org.opencontainers.image.version="${WIZ_VERSION}"

WORKDIR /opt

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        bash-completion \
        build-essential \
        ca-certificates \
        curl \
        git \
        iproute2 \
        jq \
        less \
        libatomic1 \
        net-tools \
        neovim \
        openssh-server \
        procps \
        python3 \
        ripgrep \
        sudo \
        tree \
        tzdata \
        unzip \
        vim \
        zip && \
    ln -snf "/usr/share/zoneinfo/$TZ" /etc/localtime && \
    printf '%s\n' "$TZ" > /etc/timezone && \
    mkdir -p /run/sshd /root/.ssh /etc/ssh/sshd_config.d && \
    chmod 0700 /root/.ssh && \
    printf '%s\n' \
        'PermitRootLogin prohibit-password' \
        'PasswordAuthentication no' \
        > /etc/ssh/sshd_config.d/99-wiz-spring.conf && \
    git config --global init.defaultBranch main && \
    rm -rf /var/lib/apt/lists/*

COPY --from=node /usr/local/ /usr/local/
COPY --from=builder /opt/wiz-spring/wiz-spring.jar /opt/wiz-spring/wiz-spring.jar
COPY wiz-spring-cli /usr/local/bin/wiz-spring
COPY bashrc-custom /root/bashrc-custom
COPY docker-entrypoint.sh /docker-entrypoint.sh
COPY docker-entrypoint-bind.sh /docker-entrypoint-bind.sh

RUN chmod +x /usr/local/bin/wiz-spring /docker-entrypoint.sh /docker-entrypoint-bind.sh && \
    cat /root/bashrc-custom >> /root/.bashrc && \
    rm -f /root/bashrc-custom && \
    case "$INSTALL_CODEX" in \
        true) npm install --global --no-audit --no-fund "@openai/codex@$CODEX_VERSION" ;; \
        false) ;; \
        *) printf 'INSTALL_CODEX must be true or false\n' >&2; exit 1 ;; \
    esac

EXPOSE 22 3000

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl --fail --silent --show-error "http://127.0.0.1:${WIZ_PORT:-3000}/actuator/health" >/dev/null || exit 1

ENTRYPOINT ["/docker-entrypoint.sh"]
CMD ["serve"]

FROM runtime-tools AS workspace-builder

RUN wiz-spring create "$APP_ROOT" --package "$WIZ_PACKAGE_ROOT" && \
    wiz-spring codex \
        --root "$APP_ROOT" \
        --runtime-jar "$WIZ_RUNTIME_JAR"

RUN --mount=type=bind,from=wiz-spring-instruction,source=/,target=/tmp/wiz-spring-instruction,readonly \
    test -f /tmp/wiz-spring-instruction/copilot-instructions.md && \
    rm -rf "$APP_ROOT/.github" && \
    mkdir -p "$APP_ROOT/.github" && \
    cp -a /tmp/wiz-spring-instruction/. "$APP_ROOT/.github/" && \
    rm -rf "$APP_ROOT/.github/.git"

FROM runtime-tools AS runtime-bind

ENV WIZ_SEED_DIR=/opt/app.seed

COPY --from=workspace-builder /root/.m2/ /root/.m2/
COPY --from=workspace-builder /opt/app/ /opt/app.seed/

RUN mkdir -p "$DATA_DIR" && \
    ln -s "$DATA_DIR/$APP_DIR" "$APP_ROOT" && \
    rm -f /etc/ssh/ssh_host_*

WORKDIR /opt
VOLUME ["/mnt/data"]
ENTRYPOINT ["/docker-entrypoint-bind.sh"]

FROM runtime-tools AS runtime

COPY --from=workspace-builder /root/.m2/ /root/.m2/
COPY --from=workspace-builder /opt/app/ /opt/app/

# Generate unique SSH host keys in each container instead of baking shared keys into the image.
RUN rm -f /etc/ssh/ssh_host_*

WORKDIR /opt/app
