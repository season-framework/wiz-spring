# syntax=docker/dockerfile:1.7

ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21-noble
ARG JAVA_IMAGE=eclipse-temurin:21-jdk-noble
ARG NODE_IMAGE=node:24.15.0-bookworm-slim
ARG WIZ_VERSION=1.0.0

FROM ${NODE_IMAGE} AS node-toolchain

FROM ${MAVEN_IMAGE} AS builder

ARG WIZ_VERSION
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod 0755 mvnw && \
    ./mvnw --batch-mode --no-transfer-progress dependency:go-offline

COPY src/ src/
RUN ./mvnw --batch-mode --no-transfer-progress -DskipTests clean package && \
    test -s "target/wiz-spring-${WIZ_VERSION}.jar" && \
    install -D -m 0644 "target/wiz-spring-${WIZ_VERSION}.jar" /opt/wiz-spring/wiz-spring.jar

FROM ${JAVA_IMAGE}

ARG WIZ_VERSION

LABEL org.opencontainers.image.title="WIZ Spring project generator" \
      org.opencontainers.image.version="${WIZ_VERSION}"

RUN apt-get update && \
    apt-get install -y --no-install-recommends ca-certificates git openssh-client && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd --gid 10001 wiz && \
    useradd --uid 10001 --gid 10001 --create-home --shell /bin/bash wiz && \
    install -d -o wiz -g wiz /workspace

COPY --from=builder --chown=wiz:wiz /opt/wiz-spring/wiz-spring.jar /opt/wiz-spring/wiz-spring.jar
COPY --from=node-toolchain /usr/local/bin/node /usr/local/bin/node
COPY --from=node-toolchain /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm && \
    ln -s ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx && \
    java -version && javac -version && node --version && npm --version

WORKDIR /workspace
USER 10001:10001

ENTRYPOINT ["java", "-jar", "/opt/wiz-spring/wiz-spring.jar"]
CMD ["--help"]
