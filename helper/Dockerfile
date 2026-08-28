# syntax=docker/dockerfile:1.7

FROM golang:1.24-bookworm AS build
WORKDIR /src
COPY go.mod ./
COPY cmd ./cmd
COPY internal ./internal
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/wiz-spring-helper ./cmd/wiz-spring-helper

FROM build AS template-bundle
ARG WIZ_HELPER_TEMPLATE_FILE=registry.json
COPY templates /template-source
RUN printf '%s' "$WIZ_HELPER_TEMPLATE_FILE" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9._-]*\.json$' \
    && test -f "/template-source/$WIZ_HELPER_TEMPLATE_FILE" \
    && /out/wiz-spring-helper stage-templates "/template-source/$WIZ_HELPER_TEMPLATE_FILE" /template-bundle

FROM eclipse-temurin:21-jdk-jammy AS java

FROM node:24.15.0-bookworm-slim
COPY --from=java /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH=/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

RUN groupadd --gid 10001 wizhelper \
    && useradd --uid 10001 --gid 10001 --home-dir /nonexistent --shell /usr/sbin/nologin wizhelper \
    && install -d -o 10001 -g 10001 -m 0700 /work \
    && install -d -o 0 -g 0 -m 0755 /opt/wiz-source

COPY --from=build /out/wiz-spring-helper /usr/local/bin/wiz-spring-helper
COPY --chmod=0755 docker/entrypoint.sh /usr/local/bin/wiz-spring-helper-entrypoint
COPY --from=template-bundle /template-bundle /opt/wiz-helper/templates

RUN find /opt/wiz-helper/templates -type d -exec chmod 0555 {} + \
    && find /opt/wiz-helper/templates -type f -exec sh -c \
       'for file do if [ -x "$file" ]; then chmod 0555 "$file"; else chmod 0444 "$file"; fi; done' sh {} +

EXPOSE 8080
ENV WIZ_HELPER_ADDR=0.0.0.0:8080
ENV WIZ_HELPER_WORK_DIR=/work
ENV WIZ_SPRING_SOURCE_JAR=/opt/wiz-source/wiz-spring.jar
ENV WIZ_HELPER_TEMPLATE_REGISTRY=/opt/wiz-helper/templates/registry.json

RUN /usr/local/bin/wiz-spring-helper validate-templates

ENTRYPOINT ["/usr/local/bin/wiz-spring-helper-entrypoint"]
