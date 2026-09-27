package com.gpstore.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Optional OpenAI-compatible intent interpreter with a deterministic fallback.
 *
 * The remote provider receives only the customer's prompt. It never receives
 * inventory, prices, customer identity, coordinates, credentials, or authority
 * to perform a marketplace action. Malformed responses and provider failures
 * fall back to local parsing so ordinary discovery remains available.
 */
@Component
@Primary
public class ConfiguredMarketplaceAiProvider implements MarketplaceAiProvider {
    private static final Logger log = LoggerFactory.getLogger(ConfiguredMarketplaceAiProvider.class);
    private static final int MAX_PROMPT_LENGTH = 1_000;

    private final FallbackAiProvider fallback;
    private final ObjectMapper json;
    private final HttpClient http;
    private final boolean enabled;
    private final String providerName;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final int maxAttempts;

    public ConfiguredMarketplaceAiProvider(
            FallbackAiProvider fallback,
            ObjectMapper json,
            @Value("${marketplace.ai.enabled:false}") boolean enabled,
            @Value("${marketplace.ai.provider:DISABLED}") String providerName,
            @Value("${marketplace.ai.endpoint:}") String endpoint,
            @Value("${marketplace.ai.api-key:}") String apiKey,
            @Value("${marketplace.ai.model:}") String model,
            @Value("${marketplace.ai.timeout-ms:5000}") int timeoutMs,
            @Value("${marketplace.ai.max-attempts:2}") int maxAttempts) {
        this(fallback, json, enabled, providerName, endpoint, apiKey, model,
                Duration.ofMillis(Math.min(Math.max(timeoutMs, 2_000), 30_000)),
                Math.min(Math.max(maxAttempts, 1), 3),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    ConfiguredMarketplaceAiProvider(
            FallbackAiProvider fallback, ObjectMapper json, boolean enabled,
            String providerName, String endpoint, String apiKey, String model,
            Duration timeout, int maxAttempts, HttpClient http) {
        this.fallback = fallback;
        this.json = json;
        this.enabled = enabled;
        this.providerName = providerName == null ? "" : providerName.trim();
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
        this.timeout = timeout;
        this.maxAttempts = maxAttempts;
        this.http = http;
    }

    @Override
    public Optional<Intent> interpret(String prompt) {
        Optional<Intent> local = fallback.interpret(prompt);
        if (!configured() || prompt == null || prompt.isBlank()) return local;
        try {
            String bounded = prompt.trim();
            if (bounded.length() > MAX_PROMPT_LENGTH) {
                bounded = bounded.substring(0, MAX_PROMPT_LENGTH);
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body(bounded))))
                    .build();
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                HttpResponse<String> response =
                        http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    Optional<Intent> parsed = parse(response.body());
                    if (parsed.isPresent()) return parsed;
                } else {
                    log.warn("Marketplace AI provider returned HTTP {}", response.statusCode());
                    if (response.statusCode() < 500 && response.statusCode() != 429) break;
                }
            }
            return local;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return local;
        } catch (Exception failure) {
            log.warn("Marketplace AI intent interpretation failed: {}",
                    failure.getClass().getSimpleName());
            return local;
        }
    }

    @Override
    public String name() {
        return configured() ? "OPENAI_COMPATIBLE_WITH_FALLBACK" : fallback.name();
    }

    private boolean configured() {
        return enabled && "OPENAI_COMPATIBLE".equalsIgnoreCase(providerName)
                && !endpoint.isBlank() && !apiKey.isBlank() && !model.isBlank();
    }

    private Map<String, Object> body(String prompt) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("query", "requiredItems", "attributes", "language"),
                "properties", Map.of(
                        "query", Map.of("type", "string"),
                        "categoryId", Map.of("type", List.of("integer", "null")),
                        "budget", Map.of("type", List.of("number", "null")),
                        "quantity", Map.of("type", List.of("integer", "null")),
                        "commerceMode", Map.of("type", List.of("string", "null"),
                                "enum", java.util.Arrays.asList("ONLINE_PURCHASE", "VISIT_TO_BUY",
                                        "SERVICE_AT_SHOP", null)),
                        "attributes", Map.of("type", "object",
                                "additionalProperties", Map.of("type", "string")),
                        "requiredItems", Map.of("type", "array",
                                "items", Map.of("type", "string"), "maxItems", 30),
                        "language", Map.of("type", "string",
                                "enum", List.of("ENGLISH", "HINDI", "HINGLISH"))));
        return Map.of(
                "model", model,
                "temperature", 0,
                "messages", List.of(
                        Map.of("role", "system", "content",
                                "Extract shopping intent only. Never invent products, prices, shops, stock, distance, discounts, or availability."),
                        Map.of("role", "user", "content", prompt)),
                "response_format", Map.of(
                        "type", "json_schema",
                        "json_schema", Map.of("name", "marketplace_intent",
                                "strict", true, "schema", schema)));
    }

    private Optional<Intent> parse(String responseBody) {
        try {
            JsonNode root = json.readTree(responseBody);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (!content.isTextual()) return Optional.empty();
            JsonNode intent = json.readTree(content.textValue());
            String query = text(intent, "query");
            if (query == null) return Optional.empty();
            String mode = text(intent, "commerceMode");
            if (mode != null && !List.of("ONLINE_PURCHASE", "VISIT_TO_BUY", "SERVICE_AT_SHOP")
                    .contains(mode)) return Optional.empty();
            Map<String, String> attributes = new LinkedHashMap<>();
            intent.path("attributes").fields().forEachRemaining(entry -> {
                if (entry.getValue().isTextual() && attributes.size() < 20) {
                    attributes.put(entry.getKey(), entry.getValue().textValue());
                }
            });
            List<String> items = new ArrayList<>();
            for (JsonNode item : intent.path("requiredItems")) {
                if (item.isTextual() && !item.textValue().isBlank() && items.size() < 30) {
                    items.add(item.textValue().trim());
                }
            }
            Long category = intent.path("categoryId").canConvertToLong()
                    ? intent.path("categoryId").longValue() : null;
            BigDecimal budget = intent.path("budget").isNumber()
                    ? intent.path("budget").decimalValue() : null;
            Integer quantity = intent.path("quantity").canConvertToInt()
                    ? intent.path("quantity").intValue() : null;
            if (budget != null && budget.signum() <= 0) budget = null;
            if (quantity != null && (quantity <= 0 || quantity > 10_000)) quantity = null;
            return Optional.of(new Intent(query, category, budget, quantity, mode,
                    Map.copyOf(attributes), List.copyOf(items), text(intent, "language")));
        } catch (Exception malformed) {
            log.warn("Marketplace AI returned malformed structured output");
            return Optional.empty();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) return null;
        return value.textValue().trim();
    }
}
