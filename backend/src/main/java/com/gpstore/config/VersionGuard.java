package com.gpstore.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Production must know which git SHA it was built from. {@code git rev-parse}
 * on the VPS working tree is not enough — an old image can keep serving after
 * {@code git pull}. The running process has to carry the SHA.
 */
@Component
public class VersionGuard {

    private static final Logger log = LoggerFactory.getLogger(VersionGuard.class);

    private final AppBuildInfo buildInfo;

    public VersionGuard(AppBuildInfo buildInfo) {
        this.buildInfo = buildInfo;
    }

    @PostConstruct
    public void requireBuildIdentityInProduction() {
        if (!buildInfo.production()) {
            return;
        }
        if (AppBuildInfo.isUnsetCommit(buildInfo.gitCommit())) {
            throw new IllegalStateException(
                    "Refusing to start in production without GIT_COMMIT. "
                            + "The running backend must expose the git SHA it was built from "
                            + "(see /v1/api/version). Set GIT_COMMIT to the full 40-character SHA.");
        }
        if (!buildInfo.gitCommit().matches("[0-9a-fA-F]{40}")) {
            throw new IllegalStateException(
                    "Refusing to start in production with GIT_COMMIT=" + buildInfo.gitCommit()
                            + ". Production requires the full 40-character git SHA.");
        }
        log.info("Production build identity gitCommit={} version={}",
                buildInfo.gitCommit(), buildInfo.version());
    }
}
