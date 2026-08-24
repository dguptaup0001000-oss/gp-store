package com.gpstore.controller;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
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
 * waited out the 5s acquisition timeout and Render returned 502.
 *
 * The probe still runs {@code SELECT 1} when a connection is idle. When
 * every connection is busy serving customers, "busy" is ready: we return
 * 200 without queueing behind them. If the pool is not running, 503.
 */
@RestController
public class HealthController {

    static final long READY_OK_CACHE_MS = 2_000;

    private final DataSource dataSource;
    private final AtomicLong lastReadyOkAt = new AtomicLong(0);

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
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
            if (mx != null && mx.getIdleConnections() <= 0) {
                // Serving traffic with a full pool is the healthy overloaded
                // state. Do not borrow a connection just to prove it.
                lastReadyOkAt.set(now);
                return ResponseEntity.ok(Map.of("status", "ready"));
            }
        }

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
            lastReadyOkAt.set(System.currentTimeMillis());
            return ResponseEntity.ok(Map.of("status", "ready"));
        } catch (SQLException unavailable) {
            return notReady();
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
