FROM eclipse-temurin:21-jre

ARG APP_ARTIFACT=application.__WIZ_ARTIFACT_TYPE__
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin app \
    && mkdir -p /app/data \
    && chown -R 10001:10001 /app
WORKDIR /app
ENV APP_ARTIFACT=${APP_ARTIFACT}
COPY --chown=10001:10001 app/${APP_ARTIFACT} /app/${APP_ARTIFACT}
COPY --chown=10001:10001 config /app/config
COPY --chown=10001:10001 public /app/public
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["/bin/sh", "-c", "exec java -jar \"/app/${APP_ARTIFACT}\" --spring.config.additional-location=optional:file:/app/config/"]
