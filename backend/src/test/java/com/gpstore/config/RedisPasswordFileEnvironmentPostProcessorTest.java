package com.gpstore.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class RedisPasswordFileEnvironmentPostProcessorTest {

    @TempDir
    Path tempDir;

    @Test
    void fileFillsPasswordWhenEnvIsEmpty() throws Exception {
        Path file = tempDir.resolve("redis_password");
        Files.writeString(file, "from-file-secret\n");

        MockEnvironment env = new MockEnvironment();
        env.setProperty("REDIS_PASSWORD_FILE", file.toString());

        new RedisPasswordFileEnvironmentPostProcessor()
                .postProcessEnvironment(env, mock(SpringApplication.class));

        assertEquals("from-file-secret", env.getProperty("REDIS_PASSWORD"));
        assertEquals("from-file-secret", env.getProperty("spring.data.redis.password"));
    }

    @Test
    void existingEnvPasswordWins() throws Exception {
        Path file = tempDir.resolve("redis_password");
        Files.writeString(file, "from-file-secret\n");

        MockEnvironment env = new MockEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "REDIS_PASSWORD", "already-set",
                "REDIS_PASSWORD_FILE", file.toString())));

        new RedisPasswordFileEnvironmentPostProcessor()
                .postProcessEnvironment(env, mock(SpringApplication.class));

        assertEquals("already-set", env.getProperty("REDIS_PASSWORD"));
    }

    @Test
    void missingFileFailsClosed() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("REDIS_PASSWORD_FILE", tempDir.resolve("missing").toString());

        assertThrows(IllegalStateException.class, () ->
                new RedisPasswordFileEnvironmentPostProcessor()
                        .postProcessEnvironment(env, mock(SpringApplication.class)));
    }
}
