package com.gpstore.upload;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * S3-compatible enough to receive a SigV4 presigned PUT. Returns 403 when
 * a header listed in {@code X-Amz-SignedHeaders} is missing from the
 * request — the production R2 failure mode.
 */
final class PresignedPutStub implements AutoCloseable {

    private final HttpServer server;

    PresignedPutStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            Map<String, String> query = query(exchange.getRequestURI());
            String signed = query.get("x-amz-signedheaders");
            if (signed != null) {
                for (String name : signed.split(";")) {
                    String header = name.trim().toLowerCase(Locale.ROOT);
                    if (header.isEmpty() || header.equals("host") || header.equals("content-length")) {
                        continue;
                    }
                    if (exchange.getRequestHeaders().getFirst(header) == null) {
                        exchange.sendResponseHeaders(403, -1);
                        return;
                    }
                }
            }
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, -1);
        } finally {
            exchange.close();
        }
    }

    static Map<String, String> query(URI uri) {
        Map<String, String> out = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
            String value = URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(key, value);
        }
        return out;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
