# Stage 1: build - has Maven + JDK, discarded after building the jar.
# Keeps the final runtime image small and free of build tooling.
FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /app

# Copy just the Maven files first so dependency downloads are cached in a
# separate Docker layer - only re-downloads when pom.xml actually changes,
# not on every source code edit.
COPY pom.xml .
COPY .mvn/ .mvn/
COPY mvnw .
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

COPY src/ src/
# Copy the executable Boot jar only (not *.jar.original from repackage).
RUN ./mvnw clean package -DskipTests -B \
    && cp target/backend-0.0.1-SNAPSHOT.jar /app/app.jar

# Stage 2: runtime - just a JRE (not a full JDK) and the built jar.
FROM eclipse-temurin:21-jre-jammy

ARG GIT_COMMIT=unknown
ARG APP_VERSION=0.0.1-SNAPSHOT

WORKDIR /app

# curl is only for the Docker HEALTHCHECK against /v1/actuator/health.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Runs as a non-root user - a container running as root is a real,
# unnecessary privilege-escalation risk if the app is ever compromised.
RUN useradd --system --create-home appuser

COPY --from=build /app/app.jar app.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod 755 /app/docker-entrypoint.sh \
    && chown appuser:appuser /app/app.jar /app/docker-entrypoint.sh

USER appuser

EXPOSE 8081

# All the real secrets/config still come from environment variables at
# runtime (DB_URL, DB_USERNAME, DB_PASSWORD, JWT_SECRET, etc.) - nothing
# sensitive is baked into this image.
#
# JVM flags are applied in docker-entrypoint.sh. Names below must remain in
# this file so ResourceCeilingTest can see the ceilings. Override per host
# without a rebuild (Hostinger KVM 2: JVM_ACTIVE_PROCESSORS=2, G1).
ENV MALLOC_ARENA_MAX=2
ENV JVM_MAX_RAM_PERCENT=35
# -XX:MaxRAMPercentage / -XX:MaxMetaspaceSize / -XX:ReservedCodeCacheSize
# -XX:MaxDirectMemorySize / -Xss / -XX:ActiveProcessorCount
ENV JVM_MAX_METASPACE=256m
ENV JVM_CODE_CACHE=64m
ENV JVM_MAX_DIRECT=32m
ENV JVM_THREAD_STACK=512k
ENV JVM_ACTIVE_PROCESSORS=1
ENV JVM_GC=-XX:+UseSerialGC
ENV JVM_OOM_BEHAVIOUR=-XX:+ExitOnOutOfMemoryError
ENV GIT_COMMIT=${GIT_COMMIT}
ENV APP_VERSION=${APP_VERSION}

# Context-path is /v1 (see application.properties). Actuator health is
# public; other actuator endpoints require ADMIN.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=5 \
    CMD curl -fsS http://127.0.0.1:8081/v1/actuator/health >/dev/null || exit 1

ENTRYPOINT ["/app/docker-entrypoint.sh"]
