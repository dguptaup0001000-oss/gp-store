package com.gpstore.security;

import com.gpstore.config.SecurityConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORS_ALLOWED_ORIGINS is typed by a human into a deployment console, and
 * "https://a.com, https://b.com" is how a human writes a list.
 *
 * Before the trim, the second entry was stored as " https://b.com" with a
 * leading space and matched no real Origin header ever sent. The failure is
 * invisible server-side - no log, no error - and surfaces only as a browser
 * CORS rejection on one of two origins, which is a genuinely nasty thing to
 * track down.
 *
 * Built directly rather than through @SpringBootTest: the question is purely
 * how one string is parsed, and a full application context would cost twenty
 * seconds to answer it.
 */
class CorsOriginParsingTest {

    private List<String> originsFor(String configured) {
        SecurityConfig config = new SecurityConfig(null, null, null);
        ReflectionTestUtils.setField(config, "allowedOrigins", configured);

        UrlBasedCorsConfigurationSource source =
                (UrlBasedCorsConfigurationSource) config.corsConfigurationSource();
        CorsConfiguration cors = source.getCorsConfigurations().get("/**");
        return cors.getAllowedOrigins();
    }

    @Test
    @DisplayName("origins separated by comma-space are both recognised")
    void whitespaceAfterCommaIsTrimmed() {
        assertEquals(List.of("https://a.com", "https://b.com"),
                originsFor("https://a.com, https://b.com"));
    }

    @Test
    @DisplayName("leading and trailing whitespace around the whole value is trimmed")
    void surroundingWhitespaceIsTrimmed() {
        assertEquals(List.of("https://a.com"), originsFor("  https://a.com  "));
    }

    @Test
    @DisplayName("a single origin still works unchanged")
    void singleOriginIsUnaffected() {
        assertEquals(List.of("https://gp-store.example"), originsFor("https://gp-store.example"));
    }

    @Test
    @DisplayName("empty entries from a trailing comma are dropped, not stored as blanks")
    void blankEntriesAreDropped() {
        assertEquals(List.of("https://a.com"), originsFor("https://a.com,"));
        assertEquals(List.of("https://a.com", "https://b.com"),
                originsFor("https://a.com,,https://b.com"));
    }

    @Test
    @DisplayName("an empty configuration allows NOTHING - it must never fall back to a wildcard")
    void emptyConfigurationAllowsNothing() {
        // The direction of failure matters more than the failure. Allowing
        // nothing breaks a browser client loudly and safely; falling back to
        // "*" while allowCredentials is true would let any site on the
        // internet read authenticated responses.
        assertEquals(List.of(), originsFor(""));
        assertEquals(List.of(), originsFor("   "));
    }

    @Test
    @DisplayName("no wildcard is ever produced, and credentials stay enabled")
    void wildcardIsNeverIntroduced() {
        SecurityConfig config = new SecurityConfig(null, null, null);
        ReflectionTestUtils.setField(config, "allowedOrigins", "https://a.com, https://b.com");

        UrlBasedCorsConfigurationSource source =
                (UrlBasedCorsConfigurationSource) config.corsConfigurationSource();
        CorsConfiguration cors = source.getCorsConfigurations().get("/**");

        assertFalse(cors.getAllowedOrigins().contains("*"),
                "a wildcard origin with allowCredentials=true lets any site read authenticated responses");
        assertEquals(Boolean.TRUE, cors.getAllowCredentials());
        assertEquals(List.of("Authorization", "Content-Type", "Idempotency-Key"), cors.getAllowedHeaders());
    }
}
