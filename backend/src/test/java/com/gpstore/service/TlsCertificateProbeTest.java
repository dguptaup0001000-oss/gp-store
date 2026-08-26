package com.gpstore.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlsCertificateProbeTest {

    @Test
    @DisplayName("blank host skips the network probe and stays healthy")
    void disabledHostIsHealthy() {
        TlsCertificateProbe probe = new TlsCertificateProbe(" ", 443, 14);
        Map<String, Object> body = probe.probe();
        assertEquals(false, body.get("configured"));
        assertEquals(true, body.get("healthy"));
    }

    @Test
    @DisplayName("unreachable host is unhealthy without throwing")
    void unreachableHostIsUnhealthy() {
        TlsCertificateProbe probe = new TlsCertificateProbe("127.0.0.1", 1, 14);
        Map<String, Object> body = probe.probe();
        assertEquals(true, body.get("configured"));
        assertEquals(false, body.get("healthy"));
        assertTrue(body.get("reason").toString().contains("Could not read certificate"));
    }
}
