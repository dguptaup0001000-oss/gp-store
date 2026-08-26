package com.gpstore.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads {@code REDIS_PASSWORD_FILE} (Compose Docker secret) when
 * {@code REDIS_PASSWORD} is not already in the environment.
 *
 * Production Compose no longer puts the Redis password in container Env
 * (visible via {@code docker inspect}). The file is mounted at
 * {@code /run/secrets/redis_password}. This processor copies the contents
 * into {@code REDIS_PASSWORD} / {@code spring.data.redis.password} so the
 * rest of the app, including {@link ProductionSecretsGuard}, keeps working.
 *
 * The password is never logged.
 */
public class RedisPasswordFileEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String PROPERTY_SOURCE_NAME = "redisPasswordFile";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String existing = environment.getProperty("REDIS_PASSWORD");
        if (existing != null && !existing.isBlank()) {
            return;
        }
        String file = environment.getProperty("REDIS_PASSWORD_FILE");
        if (file == null || file.isBlank()) {
            return;
        }
        Path path = Path.of(file);
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException(
                    "REDIS_PASSWORD_FILE does not exist or is not a file. "
                            + "Materialize backend/.secrets/redis_password from backend/.env "
                            + "(see backend/docker/redis/materialize-password-file.py).");
        }
        String password;
        try {
            password = Files.readString(path, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read REDIS_PASSWORD_FILE.", e);
        }
        if (password.isEmpty()) {
            throw new IllegalStateException("REDIS_PASSWORD_FILE is empty.");
        }
        Map<String, Object> map = new HashMap<>();
        map.put("REDIS_PASSWORD", password);
        map.put("spring.data.redis.password", password);
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, map));
    }
}
