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
ENTRYPOINT ["java", "-jar", "app.jar"]
