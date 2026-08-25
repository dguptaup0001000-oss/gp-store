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
RUN ./mvnw clean package -DskipTests -B

# Stage 2: runtime - just a JRE (not a full JDK) and the built jar.
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Runs as a non-root user - a container running as root is a real,
# unnecessary privilege-escalation risk if the app is ever compromised.
RUN useradd --system --create-home appuser
USER appuser

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8081

# All the real secrets/config still come from environment variables at
# runtime (DB_URL, DB_USERNAME, DB_PASSWORD, JWT_SECRET, etc.) - nothing
# sensitive is baked into this image. See docker-compose.yml for local dev
# defaults, or your real deployment platform's env var settings for prod.
# Glibc allocates a separate malloc arena per thread, up to 8 x CPU count, and
# each one grows independently. On a container this is pure RSS inflation that
# never shows up in any JVM metric - the heap looks healthy while the process
# gets OOM-killed. Two arenas costs a little allocator contention and saves
# tens of megabytes.
ENV MALLOC_ARENA_MAX=2

# JVM SIZING, and every one of these is here because the container was being
# OOM-killed rather than because the numbers look tidy.
#
# THE EVIDENCE. On a 512 MB instance the process sat at ~95% of the limit with
# CPU at ~0% - idle, and nearly out of memory. Measured on a running instance:
#
#     RSS         666 MB
#     Metaspace   124 MB used, 1179 MB RESERVED, no ceiling
#     CodeCache   240 MB reserved, no ceiling
#
# The default heap is 25% of the container, so ~128 MB - and everyone stops
# reading there. The other three quarters are metaspace, code cache, thread
# stacks, GC structures and direct buffers, none of which the heap setting
# touches and none of which had a ceiling. That is the whole 502 story: the
# container crosses its memory limit, the supervisor kills it, the proxy has nothing
# to talk to and answers 502. The two memory dips on the graph are exactly
# those restarts.
#
# Every value is overridable, because the right numbers depend on the instance
# size and this must be tunable without a rebuild.
ENV JVM_MAX_RAM_PERCENT=35
# CEILINGS, NOT TARGETS, and the difference matters when reading these.
# Metaspace was measured at 124 MB USED against 1179 MB reserved with no
# limit; capping it does not shrink the 124 MB, it stops a class-loader leak
# from ever reaching the 512 MB line. 256m leaves room for the classes this
# app genuinely loads and still bounds the runaway case.
ENV JVM_MAX_METASPACE=256m
# 64m rather than tighter on purpose. When the code cache fills, HotSpot
# prints "CodeCache is full. Compiler has been disabled" and stops JIT
# compiling entirely - the application keeps serving, interpreted, at a
# fraction of the speed, and it looks like a mysterious slowdown rather than
# a memory setting. A Spring Boot app of this size uses roughly 40 MB.
ENV JVM_CODE_CACHE=64m
ENV JVM_MAX_DIRECT=32m
ENV JVM_THREAD_STACK=512k
# 0.5 CPU. Without this the JVM sizes its GC threads, its common ForkJoinPool
# and several internal pools from the HOST's core count, not the share this
# container actually gets - so a half-core container builds thread pools for a
# machine it cannot use, and pays for every one of those stacks.
ENV JVM_ACTIVE_PROCESSORS=1
# SerialGC below one CPU is not a downgrade. G1's concurrent phases need cores
# to run on; with half of one they interleave with application work and add
# both pause time and native bookkeeping. Serial is smaller and more
# predictable at this size. Set JVM_GC to -XX:+UseG1GC when the instance grows.
ENV JVM_GC=-XX:+UseSerialGC

# Fail fast on OOM rather than thrashing. Without this the JVM spends minutes
# at 99% memory doing nothing but full GCs - the flat 95% line on the graph -
# while requests time out and the platform cannot tell it is unhealthy. A
# clean exit gets a clean restart.
ENV JVM_OOM_BEHAVIOUR=-XX:+ExitOnOutOfMemoryError

ENTRYPOINT ["sh", "-c", "exec java \
  -XX:MaxRAMPercentage=${JVM_MAX_RAM_PERCENT} \
  -XX:MaxMetaspaceSize=${JVM_MAX_METASPACE} \
  -XX:ReservedCodeCacheSize=${JVM_CODE_CACHE} \
  -XX:MaxDirectMemorySize=${JVM_MAX_DIRECT} \
  -Xss${JVM_THREAD_STACK} \
  -XX:ActiveProcessorCount=${JVM_ACTIVE_PROCESSORS} \
  ${JVM_GC} \
  ${JVM_OOM_BEHAVIOUR} \
  -XX:+UseContainerSupport \
  -Djava.security.egd=file:/dev/./urandom \
  ${JAVA_OPTS} \
  -jar app.jar"]
