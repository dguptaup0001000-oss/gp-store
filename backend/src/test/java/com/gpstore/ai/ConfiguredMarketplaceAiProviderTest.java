package com.gpstore.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ConfiguredMarketplaceAiProviderTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void structuredProviderCanInterpretIntentWithoutSupplyingMarketplaceFacts() throws Exception {
        String content = """
                {"query":"red shoes","categoryId":null,"budget":2000,"quantity":null,
                 "commerceMode":null,"attributes":{"color":"red"},
                 "requiredItems":[],"language":"ENGLISH"}
                """.replace("\n", "");
        start("{\"choices\":[{\"message\":{\"content\":"
                + new ObjectMapper().writeValueAsString(content) + "}}]}");

        MarketplaceAiProvider provider = provider(true);
        MarketplaceAiProvider.Intent intent =
                provider.interpret("Show red shoes below ₹2000 near me").orElseThrow();

        assertThat(intent.query()).isEqualTo("red shoes");
        assertThat(intent.budget()).isEqualByComparingTo("2000");
        assertThat(intent.attributes()).containsEntry("color", "red");
        assertThat(provider.name()).isEqualTo("OPENAI_COMPATIBLE_WITH_FALLBACK");
    }

    @Test
    void malformedProviderResponseFallsBackToDeterministicInterpretation() throws Exception {
        start("{\"choices\":[{\"message\":{\"content\":\"not-json\"}}]}");

        MarketplaceAiProvider.Intent intent = provider(true)
                .interpret("ingredients for paneer butter masala under ₹1000")
                .orElseThrow();

        assertThat(intent.requiredItems()).contains("paneer", "butter");
        assertThat(intent.budget()).isEqualByComparingTo("1000");
    }

    @Test
    void disabledProviderNeverCallsRemoteService() throws Exception {
        start("{\"unexpected\":\"response\"}");

        MarketplaceAiProvider.Intent intent = provider(false)
                .interpret("iPhone repair nearby").orElseThrow();

        assertThat(intent.commerceMode()).isEqualTo("SERVICE_AT_SHOP");
    }

    private ConfiguredMarketplaceAiProvider provider(boolean enabled) {
        return new ConfiguredMarketplaceAiProvider(
                new FallbackAiProvider(), new ObjectMapper(), enabled,
                "OPENAI_COMPATIBLE", endpoint(), "server-only-test-key", "intent-model",
                Duration.ofSeconds(2), 2, HttpClient.newHttpClient());
    }

    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/chat";
    }

    private void start(String response) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }
}
