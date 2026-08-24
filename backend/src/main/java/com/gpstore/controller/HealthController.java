package com.gpstore.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

/**
 * Liveness vs readiness.
 *
 * {@code /api/health} answers "is the JVM serving HTTP". Load balancers can
 * use it after a crash. It does not prove the database is reachable.
 *
 * {@code /api/health/ready} borrows one connection, runs {@code SELECT 1},
 * and returns it. A 503 here means the instance should not receive traffic
 * yet (or anymore). The checkout of the connection is the whole point: if
 * the pool is exhausted this fails fast instead of claiming the shop is up.
 */
@RestController
public class HealthController {

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/api/health")
    public String health() {
        return "GP-STORE Backend Running Successfully!";
    }

    @GetMapping("/api/health/ready")
    public ResponseEntity<Map<String, String>> ready() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
            return ResponseEntity.ok(Map.of("status", "ready"));
        } catch (SQLException unavailable) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "not-ready"));
        }
    }
}
