package com.gpstore.perf;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tomcat gzip is not exercised by MockMvc or by reading application.properties.
 * A 5k-VU browse flood transferred ~201 GB when JSON left the instance
 * uncompressed; this hits a real port with Accept-Encoding: gzip.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
class GzipCompressionHttpTest {

    private static final String FIXTURE_PREFIX = "GzipProof Category ";

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;

    private final List<Long> inserted = new ArrayList<>();

    @AfterEach
    void removeFixtures() {
        for (Long id : inserted) {
            jdbc.update("DELETE FROM categories WHERE id = ?", id);
        }
        inserted.clear();
    }

    @Test
    @DisplayName("catalog JSON is actually gzipped on the wire")
    void catalogJsonIsGzipped() throws Exception {
        // min-response-size is 1024 bytes. Pad names so the public list
        // (capped at 100) is well above that even on an otherwise empty DB.
        String padding = "x".repeat(80);
        for (int i = 0; i < 40; i++) {
            Long id = jdbc.queryForObject(
                    """
                    INSERT INTO categories (name, description, active)
                    VALUES (?, ?, true)
                    RETURNING id
                    """,
                    Long.class,
                    FIXTURE_PREFIX + i + " " + padding,
                    "gzip-proof");
            inserted.add(id);
        }

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        String url = "http://localhost:" + port + "/v1/api/categories";

        HttpResponse<byte[]> identity = client.send(
                HttpRequest.newBuilder(URI.create(url))
                        .header("Accept-Encoding", "identity")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        HttpResponse<byte[]> gzipped = client.send(
                HttpRequest.newBuilder(URI.create(url))
                        .header("Accept-Encoding", "gzip")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(200, identity.statusCode());
        assertEquals(200, gzipped.statusCode());
        assertTrue(identity.body().length >= 1024,
                "fixture list must exceed compression min-response-size, was " + identity.body().length);
        String encoding = gzipped.headers().firstValue("Content-Encoding").orElse("");
        assertTrue(encoding.toLowerCase().contains("gzip"),
                "Content-Encoding must be gzip, was '" + encoding + "' body starts "
                        + new String(gzipped.body(), 0, Math.min(40, gzipped.body().length), StandardCharsets.ISO_8859_1));
        assertTrue(gzipped.body().length < identity.body().length,
                "gzip body (" + gzipped.body().length + ") must be smaller than identity ("
                        + identity.body().length + ")");
    }
}
