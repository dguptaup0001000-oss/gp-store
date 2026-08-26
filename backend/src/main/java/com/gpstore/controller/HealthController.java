package com.gpstore.controller;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Liveness vs readiness.
 *
 * {@code /api/health} answers "is the JVM serving HTTP". Load balancers can
 * use it after a crash. It does not prove the database is reachable.
 *
 * {@code /api/health/ready} must not take a pooled connection on every
 * probe. A 5,000-VU script that hits ready once per iteration used to
 * spend the entire pool of ten on {@code SELECT 1}, so catalog requests
 * waited out the 5s acquisition timeout and the reverse proxy returned 502.
 *
 * The probe still runs {@code SELECT 1} when a connection is idle. When
 * every connection is busy serving customers AND this process has already
 * proven the database once, "busy" is ready: we return 200 without
 * queueing behind them. A JVM that has never successfully probed must not
 * skip the SELECT just because the pool looks full (hung connections to a
 * dead database would look "ready" forever). If the pool is not running, 503.
 *
 * Redis is required for cache and rate limits. A successful ready probe
 * also PINGs Redis. Unit tests construct this controller with the
 * one-argument constructor and skip that check.
 */
@RestController
public class HealthController {

    static final long READY_OK_CACHE_MS = 2_000;

    private final DataSource dataSource;
    private final StringRedisTemplate redisTemplate;
    private final AtomicLong lastReadyOkAt = new AtomicLong(0);

    public HealthController(DataSource dataSource) {
        this(dataSource, null);
    }

    @Autowired
    public HealthController(DataSource dataSource,
                            @Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.dataSource = dataSource;
        this.redisTemplate = redisTemplate;
    }

    @GetMapping("/api/health")
    public String health() {
        return "GP-STORE Backend Running Successfully!";
    }

    @GetMapping("/api/health/ready")
    public ResponseEntity<Map<String, String>> ready() {
        long now = System.currentTimeMillis();
        if (now - lastReadyOkAt.get() < READY_OK_CACHE_MS) {
            return ResponseEntity.ok(Map.of("status", "ready"));
        }

        HikariDataSource hikari = unwrapHikari();
        if (hikari != null && !hikari.isRunning()) {
            return notReady();
        }
        if (hikari != null) {
            HikariPoolMXBean mx = hikari.getHikariPoolMXBean();
            if (mx != null && mx.getIdleConnections() <= 0 && lastReadyOkAt.get() != 0) {
                // Serving traffic with a full pool is the healthy overloaded
                // state, but only after this process has actually run SELECT 1
                // at least once. Do not refresh lastReadyOkAt here: a skip is
                // not a new proof, and bumping the timestamp would hide a
                // later dead database behind a 2s "ready" cache forever.
                if (!redisReady()) {
                    return notReady();
                }
                return ResponseEntity.ok(Map.of("status", "ready"));
            }
        }

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
        } catch (SQLException unavailable) {
            return notReady();
        }

        if (!redisReady()) {
            return notReady();
        }

        lastReadyOkAt.set(System.currentTimeMillis());
        return ResponseEntity.ok(Map.of("status", "ready"));
    }

    private boolean redisReady() {
        if (redisTemplate == null) {
            return true;
        }
        RedisConnectionFactory factory = redisTemplate.getConnectionFactory();
        if (factory == null) {
            return false;
        }
        RedisConnection connection = null;
        try {
            connection = factory.getConnection();
            String pong = connection.ping();
            return pong != null && !pong.isBlank();
        } catch (RuntimeException unavailable) {
            return false;
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (RuntimeException ignored) {
                    // Closing a dead connection must not hide the PING result.
                }
            }
        }
    }

    private static ResponseEntity<Map<String, String>> notReady() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", "not-ready"));
    }

    private HikariDataSource unwrapHikari() {
        if (dataSource instanceof HikariDataSource hikari) {
            return hikari;
        }
        try {
            return dataSource.unwrap(HikariDataSource.class);
        } catch (SQLException ignored) {
            return null;
        }
    }
}
