package com.gpstore.controller;

import com.gpstore.config.AppBuildInfo;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public build identity for deploy verification. No secrets, no config dumps.
 */
@RestController
public class VersionController {

    private final AppBuildInfo buildInfo;
    private final ObjectProvider<Flyway> flyway;

    public VersionController(AppBuildInfo buildInfo, ObjectProvider<Flyway> flyway) {
        this.buildInfo = buildInfo;
        this.flyway = flyway;
    }

    @GetMapping("/api/version")
    public Map<String, String> version() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("application", "gp-store-backend");
        body.put("version", buildInfo.version());
        body.put("gitCommit", buildInfo.gitCommit());
        body.put("binaryGitCommit", buildInfo.binaryGitCommit());
        body.put("schemaVersion", schemaVersion());
        body.put("environment", buildInfo.environmentName());
        return body;
    }

    private String schemaVersion() {
        Flyway configured = flyway.getIfAvailable();
        if (configured == null) {
            return "disabled";
        }
        try {
            MigrationInfo current = configured.info().current();
            return current == null || current.getVersion() == null
                    ? "none"
                    : current.getVersion().getVersion();
        } catch (RuntimeException ignored) {
            // Safe public diagnostics only. Never expose connection details or
            // the exception text from a failed metadata query.
            return "unavailable";
        }
    }
}
