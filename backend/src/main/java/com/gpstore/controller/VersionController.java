package com.gpstore.controller;

import com.gpstore.config.AppBuildInfo;
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

    public VersionController(AppBuildInfo buildInfo) {
        this.buildInfo = buildInfo;
    }

    @GetMapping("/api/version")
    public Map<String, String> version() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("application", "gp-store-backend");
        body.put("version", buildInfo.version());
        body.put("gitCommit", buildInfo.gitCommit());
        body.put("environment", buildInfo.environmentName());
        return body;
    }
}
