package com.gpstore.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.net.InetSocketAddress;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads the public TLS certificate for the shop API hostname.
 *
 * Used by the admin ops dashboard so Let's Encrypt expiry is visible before
 * browsers start refusing the shop. Does not expose private keys, and a
 * probe failure is reported as unhealthy rather than throwing into the
 * request that asked.
 */
@Service
public class TlsCertificateProbe {

    private static final Logger log = LoggerFactory.getLogger(TlsCertificateProbe.class);

    private final String host;
    private final int port;
    private final int warnDays;

    public TlsCertificateProbe(
            @Value("${ops.tls.check-host:api.gpstore.co.in}") String host,
            @Value("${ops.tls.check-port:443}") int port,
            @Value("${ops.tls.warn-days:14}") int warnDays) {
        this.host = host == null ? "" : host.trim();
        this.port = port;
        this.warnDays = Math.max(1, warnDays);
    }

    public Map<String, Object> probe() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("host", host.isBlank() ? null : host);
        body.put("port", port);
        if (host.isBlank()) {
            body.put("configured", false);
            body.put("healthy", true);
            body.put("reason", "TLS host check is disabled.");
            return body;
        }
        body.put("configured", true);
        try {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            try (SSLSocket socket = (SSLSocket) factory.createSocket()) {
                socket.connect(new InetSocketAddress(host, port), 3000);
                socket.setSoTimeout(3000);
                socket.startHandshake();
                Certificate[] certs = socket.getSession().getPeerCertificates();
                if (certs == null || certs.length == 0 || !(certs[0] instanceof X509Certificate x509)) {
                    body.put("healthy", false);
                    body.put("reason", "Peer did not present an X.509 certificate.");
                    return body;
                }
                Instant notAfter = x509.getNotAfter().toInstant();
                long days = Duration.between(Instant.now(), notAfter).toDays();
                boolean healthy = days >= warnDays;
                body.put("healthy", healthy);
                body.put("daysRemaining", days);
                body.put("notAfter", notAfter.toString());
                body.put("warnDays", warnDays);
                body.put("reason", healthy
                        ? "Certificate is valid for at least " + warnDays + " more days."
                        : "Certificate expires in " + days + " days.");
                return body;
            }
        } catch (Exception e) {
            log.warn("TLS probe for {}:{} failed: {}", host, port, e.getClass().getSimpleName());
            body.put("healthy", false);
            body.put("reason", "Could not read certificate (" + e.getClass().getSimpleName() + ").");
            return body;
        }
    }
}
