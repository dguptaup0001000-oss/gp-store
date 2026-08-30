package com.gpstore.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraefikForwardedHeadersTest {

    @Test
    void websecureTrustsCloudflareRangesAndIsNotInsecure() throws IOException {
        String yaml = Files.readString(Path.of("docker-compose.yml"));
        assertTrue(yaml.contains("entrypoints.websecure.forwardedHeaders.trustedIPs="),
                "Traefik must pin Cloudflare CIDRs on websecure");
        assertTrue(yaml.contains("entrypoints.web.forwardedHeaders.trustedIPs="),
                "HTTP entrypoint must use the same trustedIPs (redirect + ACME)");
        assertTrue(yaml.contains("173.245.48.0/20"), "missing Cloudflare IPv4 range 173.245.48.0/20");
        assertTrue(yaml.contains("104.16.0.0/13"), "missing Cloudflare IPv4 range 104.16.0.0/13");
        assertTrue(yaml.contains("2400:cb00::/32"), "missing Cloudflare IPv6 range 2400:cb00::/32");
        assertFalse(yaml.lines().anyMatch(line ->
                        !line.trim().startsWith("#") && line.contains("forwardedHeaders.insecure")),
                "forwardedHeaders.insecure trusts every client and must not be set");
    }
}
