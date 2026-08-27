package com.gpstore.config;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/**
 * A second HTTP connector used only for {@code /v1/api/health/live}.
 *
 * Production 4,255-VU browse load timed out {@code /v1/api/health} even
 * though that handler is a constant string: the probe shared the same 80
 * catalog workers and the same accept queue. Traefik then could not tell
 * a dead JVM from a busy one.
 *
 * Port 0 (the default) disables this connector so unit tests and local
 * IDE runs do not bind 8082. Production Compose sets
 * {@code TOMCAT_LIVE_PORT=8082} and routes only the live path there.
 *
 * Eight threads is enough for Docker HEALTHCHECK + Traefik + a handful of
 * uptime probes. It is not a second catalog listener.
 */
@Component
public class TomcatLiveConnectorCustomizer
        implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    private final int livePort;

    public TomcatLiveConnectorCustomizer(
            @Value("${server.tomcat.live-port:0}") int livePort) {
        this.livePort = livePort;
    }

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        if (livePort <= 0) {
            return;
        }
        factory.addAdditionalTomcatConnectors(liveConnector());
    }

    private Connector liveConnector() {
        Connector connector = new Connector(Http11NioProtocol.class.getName());
        connector.setPort(livePort);
        connector.setThrowOnFailure(true);
        if (connector.getProtocolHandler() instanceof Http11NioProtocol protocol) {
            protocol.setMaxThreads(8);
            protocol.setMinSpareThreads(2);
            protocol.setAcceptCount(256);
            protocol.setMaxConnections(1024);
            protocol.setConnectionTimeout(2000);
            protocol.setKeepAliveTimeout(5000);
            protocol.setMaxKeepAliveRequests(10_000);
        }
        return connector;
    }
}
