package com.gpstore.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

/**
 * Identity of the running binary. Deploy verifies {@link #gitCommit()}
 * against the GitHub {@code main} SHA. No secrets belong here.
 */
@Component
public class AppBuildInfo {

    private final String version;
    private final String gitCommit;
    private final String binaryGitCommit;
    private final boolean production;

    public AppBuildInfo(
            @Value("${app.version:0.0.1-SNAPSHOT}") String version,
            @Value("${app.git-commit:unknown}") String gitCommit,
            @Value("${app.production:false}") boolean production,
            ObjectProvider<BuildProperties> buildProperties) {
        this(version, gitCommit, production, embeddedCommit(buildProperties));
    }

    /** Visible for focused tests that do not start Spring. */
    AppBuildInfo(String version, String gitCommit, boolean production) {
        this(version, gitCommit, production, "unknown");
    }

    AppBuildInfo(String version, String gitCommit, boolean production,
                 String binaryGitCommit) {
        this.version = version == null ? "" : version.trim();
        this.gitCommit = gitCommit == null ? "" : gitCommit.trim();
        this.binaryGitCommit = binaryGitCommit == null ? "" : binaryGitCommit.trim();
        this.production = production;
    }

    private static String embeddedCommit(ObjectProvider<BuildProperties> provider) {
        BuildProperties properties = provider.getIfAvailable();
        return properties == null ? "unknown" : properties.get("gitCommit");
    }

    public String version() {
        return version.isBlank() ? "0.0.1-SNAPSHOT" : version;
    }

    public String gitCommit() {
        return gitCommit;
    }

    /** SHA physically embedded in the jar by Maven during the image build. */
    public String binaryGitCommit() {
        return binaryGitCommit;
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
