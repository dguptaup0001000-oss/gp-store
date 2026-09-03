package com.gpstore.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Turns a dropped pin into the address fields a person would have typed.
 *
 * WHAT THIS IS FOR, AND WHAT IT IS NOT. It fills the form so the customer
 * edits instead of types. It is a SUGGESTION, never the truth: the delivery
 * fee is still computed server-side from the coordinates, and every field it
 * writes stays editable, because rural Kushinagar is exactly where a free
 * geocoder is thinnest and the person standing at the gate knows better.
 *
 * OPENSTREETMAP, AND THE OBLIGATIONS THAT COME WITH IT. Nominatim is free and
 * needs no key - which is why there is no secret here to leak into an APK -
 * but it is run on donated hardware under a usage policy this code has to
 * keep rather than merely acknowledge:
 *
 *   - An identifying User-Agent, so the operators can contact us rather than
 *     block us.
 *   - At most one request a second, globally. Not per user, per instance:
 *     the throttle below is a hard gate every call passes through.
 *   - No bulk or automated sweeps. This only ever runs when a person has
 *     just tapped "use my location".
 *
 * A FAILURE HERE IS NOT AN ERROR THE CUSTOMER SHOULD SEE. If OSM is slow,
 * down, or rate-limiting us, the honest outcome is an empty suggestion and a
 * form the customer fills in by hand - exactly what they do today. Nothing
 * about placing an order may depend on a third party we do not pay.
 */
@Service
public class ReverseGeocoder {

    private static final Logger log = LoggerFactory.getLogger(ReverseGeocoder.class);

    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String endpoint;
    private final String userAgent;
    private final boolean enabled;
    private final long minGapMillis;
    private final Duration timeout;

    /** The global one-a-second gate. Nominatim's policy is not per-user. */
    private final AtomicLong nextAllowedAt = new AtomicLong(0);

    public ReverseGeocoder(
            @Value("${geocoding.enabled:true}") boolean enabled,
            @Value("${geocoding.endpoint:https://nominatim.openstreetmap.org/reverse}") String endpoint,
            // MUST identify the shop. Nominatim blocks anonymous traffic, and
            // being blocked would look to us like "auto-fill stopped working"
            // with nothing in the logs to say why.
            @Value("${geocoding.user-agent:GP-STORE/1.0 (+https://api.gpstore.co.in)}") String userAgent,
            @Value("${geocoding.min-gap-ms:1100}") long minGapMillis,
            @Value("${geocoding.timeout-ms:4000}") long timeoutMillis) {
        this.enabled = enabled;
        this.endpoint = endpoint;
        this.userAgent = userAgent;
        this.minGapMillis = minGapMillis;
        this.timeout = Duration.ofMillis(timeoutMillis);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(timeoutMillis, 3000)))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * @return the fields worth pre-filling, or empty when we could not ask or
     *         did not get a usable answer. Empty is a normal outcome, not a
     *         fault, and the caller renders a blank form rather than an error.
     */
    public Optional<Map<String, String>> suggest(double latitude, double longitude) {
        if (!enabled) {
            return Optional.empty();
        }
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            // A pin off the planet is a client bug, not something to ask OSM
            // about - and asking would spend our one-a-second on nonsense.
            return Optional.empty();
        }
        if (!takeSlot()) {
            log.debug("Reverse geocode skipped: the one-per-second gate is closed.");
            return Optional.empty();
        }

        try {
            // zoom=18 is building level. addressdetails=1 is what returns the
            // parts, rather than one formatted string we would have to split.
            URI uri = URI.create(endpoint
                    + "?format=jsonv2&addressdetails=1&zoom=18"
                    + "&lat=" + latitude
                    + "&lon=" + longitude);

            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    // en so the fields come back in a script the form expects;
                    // the customer edits them into whatever they prefer.
                    .header("Accept-Language", "en")
                    .timeout(timeout)
                    .GET()
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.info("Reverse geocode returned HTTP {}; the form stays manual.",
                        response.statusCode());
                return Optional.empty();
            }
            return parse(response.body());

        } catch (java.io.IOException | InterruptedException | RuntimeException failed) {
            if (failed instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // Deliberately not an error log. A geocoder being unreachable is a
            // Tuesday, and the customer's form still works.
            log.info("Reverse geocode unavailable ({}); the form stays manual.",
                    failed.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * One caller a second gets through, and the rest are told no.
     *
     * NOT A QUEUE. Making the second customer WAIT a second for a form
     * pre-fill would be a worse experience than not pre-filling at all, and a
     * queue under load becomes a pile of threads sleeping on someone else's
     * server.
     */
    private boolean takeSlot() {
        long now = System.currentTimeMillis();
        while (true) {
            long next = nextAllowedAt.get();
            if (now < next) {
                return false;
            }
            if (nextAllowedAt.compareAndSet(next, now + minGapMillis)) {
                return true;
            }
        }
    }

    /**
     * The parts of an OSM answer that map onto this form.
     *
     * OSM'S SHAPE IS NOT OUR SHAPE, and the mapping is where rural India needs
     * care. A village address has no "city" - it has a village, or a hamlet,
     * or nothing but a district. Trying each in turn is why this fills
     * anything at all outside a town.
     */
    private Optional<Map<String, String>> parse(String body) {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode address = root.path("address");
            if (address.isMissingNode()) {
                return Optional.empty();
            }

            Map<String, String> out = new LinkedHashMap<>();
            put(out, "area", first(address, "suburb", "neighbourhood", "village", "hamlet", "locality"));
            put(out, "street", first(address, "road", "pedestrian", "residential"));
            put(out, "city", first(address, "city", "town", "municipality", "village"));
            put(out, "district", first(address, "state_district", "county", "district"));
            put(out, "state", first(address, "state", "region"));
            put(out, "pincode", first(address, "postcode"));
            put(out, "formattedAddress", text(root, "display_name"));

            return out.isEmpty() ? Optional.empty() : Optional.of(out);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException malformed) {
            log.info("Reverse geocode answer did not parse; the form stays manual.");
            return Optional.empty();
        }
    }

    private static void put(Map<String, String> out, String key, String value) {
        if (value != null && !value.isBlank()) {
            out.put(key, value.trim());
        }
    }

    private static String first(JsonNode node, String... keys) {
        for (String key : keys) {
            String value = text(node, key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        return value.isTextual() ? value.asText() : null;
    }
}
