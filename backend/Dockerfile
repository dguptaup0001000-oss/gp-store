# ARCHITECTURE: every image referenced here is an official multi-arch image
# published for linux/amd64 AND linux/arm64, so this same Dockerfile builds
# unchanged on an ARM64 VPS (Ampere/Graviton) and on an x86 CI runner. No
# --platform pin is used deliberately: pinning would force emulation on one
# of the two and silently make builds 5-10x slower.

# Stage 1: build - has Maven + JDK, discarded after building the jar.
# Keeps the final runtime image small and free of build tooling.
#
# --platform=$BUILDPLATFORM pins this stage to the BUILDER's architecture,
# never the target's. That matters enormously for a JVM app: Java bytecode is
# architecture-independent, so the jar produced on an amd64 CI runner is
# byte-for-byte usable on arm64. Without this line, a cross-build for arm64
# would run the entire Maven build - dependency resolution, compilation,
# tests - under QEMU emulation, turning a 2-minute build into 20+ minutes for
# an identical artifact. Only the small runtime stage below is emulated.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /app

# Copy just the Maven files first so dependency downloads are cached in a
# separate Docker layer - only re-downloads when pom.xml actually changes,
# not on every source code edit.
COPY pom.xml .
COPY .mvn/ .mvn/
COPY mvnw .
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

COPY src/ src/
RUN ./mvnw clean package -DskipTests -B

# Stage 2: runtime - just a JRE (not a full JDK) and the built jar.
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# curl is installed solely for the HEALTHCHECK below. The JRE image carries
# neither curl nor wget, so without this the health check silently reports
# "unhealthy" forever and any orchestrator that trusts it refuses to route
# traffic - a failure mode that looks like the app being broken.
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*

# Runs as a non-root user - a container running as root is a real,
# unnecessary privilege-escalation risk if the app is ever compromised.
RUN useradd --system --create-home appuser

# --chown so the jar is owned by the user that runs it, rather than being
# root-owned and merely world-readable.
COPY --from=build --chown=appuser:appuser /app/target/*.jar app.jar

USER appuser

EXPOSE 8081

# JVM MEMORY. Java 21 reads the container's cgroup limit automatically
# (UseContainerSupport, on by default since Java 10), but its DEFAULT heap
# is only ~25% of that limit - so a 4 GB container would run a 1 GB heap and
# leave 3 GB unused. 70% is the deliberate figure: the remaining 30% is not
# spare, it is what the JVM itself needs outside the heap - metaspace, code
# cache, thread stacks (~1 MB each, and this app runs a sizeable Tomcat
# pool), GC structures, and the direct byte buffers the Postgres and Redis
# drivers allocate for network I/O. Giving the heap 90%+ is the classic way
# to get killed by the kernel OOM reaper while the heap graph still looks
# healthy.
#
# A percentage rather than a fixed -Xmx so the same image is correct on a
# 1 GB box and a 8 GB box.
#
# ExitOnOutOfMemoryError: if the heap genuinely exhausts, die immediately and
# let the restart policy replace the container. A JVM that survives an OOM
# typically limps - some threads dead, some state half-written - and a
# half-working backend is harder to diagnose and worse for customers than a
# clean restart.
#
# JAVA_OPTS is appended last so a deployment can override any of this
# without rebuilding the image.
ENV JAVA_TOOL_OPTIONS=""
ENV JVM_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -XX:+UseG1GC"
ENV JAVA_OPTS=""

# Container-level health. Points at the app's own actuator endpoint, which
# reports the database and Redis as well as the process - so "healthy" means
# the app can actually serve, not merely that the JVM is running.
#
# The path includes /v1 because server.servlet.context-path=/v1.
#
# start-period is generous: Spring Boot with Flyway and a cold Supabase
# connection can take 30-60s on a small box, and a health check that fails
# during normal startup causes restart loops that look like crashes.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://localhost:8081/v1/actuator/health || exit 1

# Shell form via exec so the JVM stays PID 1 and receives SIGTERM directly -
# that signal is what triggers Spring Boot's graceful shutdown (see
# server.shutdown=graceful in application.properties). Wrapping the process
# in a shell that does NOT exec would leave the JVM as a child, the shell
# would swallow the signal, and every deployment would kill in-flight
# requests instead of draining them.
ENTRYPOINT ["sh", "-c", "exec java $JVM_OPTS $JAVA_OPTS -jar app.jar"]
