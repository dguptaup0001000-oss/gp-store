package com.gpstore.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientIpResolverTest {

    private static final String TRUSTED = ClientIpResolver.DEFAULT_TRUSTED_CIDRS;

    @Test
    void spoofedHeadersFromPublicPeerAreIgnoredEvenWhenTrustFlagIsOn() {
        ClientIpResolver resolver = new ClientIpResolver(true, TRUSTED);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("X-Forwarded-For", "198.51.100.1, 172.64.0.8");
        request.addHeader("CF-Connecting-IP", "198.51.100.1");

        assertEquals("203.0.113.10", resolver.resolve(request));
    }

    @Test
    void trustedProxyUsesCfConnectingIpAsTheClient() {
        ClientIpResolver resolver = new ClientIpResolver(true, TRUSTED);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.18.0.2");
        request.addHeader("CF-Connecting-IP", "198.51.100.77");
        request.addHeader("X-Forwarded-For", "203.0.113.9, 172.64.0.8");

        assertEquals("198.51.100.77", resolver.resolve(request));
    }

    @Test
    void trustedProxyFallsBackToLeftmostValidXff() {
        ClientIpResolver resolver = new ClientIpResolver(true, TRUSTED);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.7");
        request.addHeader("X-Forwarded-For", "198.51.100.42, 172.64.0.8");

        assertEquals("198.51.100.42", resolver.resolve(request));
    }

    @Test
    void trustFlagOffIgnoresForwardedHeadersFromLoopback() {
        ClientIpResolver resolver = new ClientIpResolver(false, TRUSTED);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("CF-Connecting-IP", "198.51.100.1");
        request.addHeader("X-Forwarded-For", "198.51.100.1");

        assertEquals("127.0.0.1", resolver.resolve(request));
    }

    @Test
    void hostnameInForwardedHeaderIsRejected() {
        ClientIpResolver resolver = new ClientIpResolver(true, TRUSTED);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.4");
        request.addHeader("CF-Connecting-IP", "evil.example");
        request.addHeader("X-Forwarded-For", "not-an-ip, 198.51.100.9");

        assertEquals("198.51.100.9", resolver.resolve(request));
    }

    @Test
    void rfc1918IsTrustedAndPublicIpIsNot() {
        ClientIpResolver resolver = new ClientIpResolver(true, TRUSTED);
        assertTrue(resolver.isTrustedProxy("10.1.2.3"));
        assertTrue(resolver.isTrustedProxy("172.18.0.5"));
        assertTrue(resolver.isTrustedProxy("192.168.0.1"));
        assertTrue(resolver.isTrustedProxy("127.0.0.1"));
        assertFalse(resolver.isTrustedProxy("203.0.113.10"));
        assertFalse(resolver.isTrustedProxy("8.8.8.8"));
    }
}
