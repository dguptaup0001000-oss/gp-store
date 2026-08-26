package com.gpstore.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Identity of the running binary. Deploy verifies {@link #gitCommit()}
 * against the GitHub {@code main} SHA. No secrets belong here.
 */
@Component
public class AppBuildInfo {

    private final String version;
    private final String gitCommit;
    private final boolean production;

    public AppBuildInfo(
            @Value("${app.version:0.0.1-SNAPSHOT}") String version,
            @Value("${app.git-commit:unknown}") String gitCommit,
            @Value("${app.production:false}") boolean production) {
        this.version = version == null ? "" : version.trim();
        this.gitCommit = gitCommit == null ? "" : gitCommit.trim();
        this.production = production;
    }

    public String version() {
        return version.isBlank() ? "0.0.1-SNAPSHOT" : version;
    }

    public String gitCommit() {
        return gitCommit;
    }

    public String environmentName() {
        return production ? "production" : "development";
    }

    public boolean production() {
        return production;
    }

    static boolean isUnsetCommit(String commit) {
        if (commit == null) {
            return true;
        }
        String value = commit.trim();
        return value.isEmpty()
                || "unknown".equalsIgnoreCase(value)
                || "dev".equalsIgnoreCase(value)
                || "none".equalsIgnoreCase(value);
    }
}
