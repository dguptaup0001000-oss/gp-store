package com.gpstore.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RAISING THE LIMIT FOR TESTS MUST NOT RAISE IT FOR THE SHOP.
 *
 * <p>WHY THIS EXISTS. {@code src/test/resources/config/application.properties}
 * now sets {@code rate-limit.auth-per-minute} high, because the limiter counts
 * per client address and under MockMvc the whole suite shares one - so twenty
 * per minute is a budget every test competes for, and on a fast runner
 * whichever class is registering when the window fills fails with a 429 that
 * has nothing to do with what it tests.
 *
 * <p>That overlay is exactly the kind of change that quietly becomes a
 * production one. A test profile that merges into the real configuration is
 * one careless edit away from shipping, and the symptom - an auth endpoint
 * that no longer throttles - is invisible until somebody is enumerating
 * passwords against it. So this test reads the PRODUCTION file off disk and
 * requires the real limit to still be there, and separately requires the
 * filter itself to still refuse at whatever number it is given.
 *
 * <p>It deliberately does NOT read the limit from the Spring context: in this
 * JVM that value IS the raised one. Reading the production file is the only
 * way to assert about production from inside a test profile that overrides it.
 */
@DisplayName("The production rate limiter is untouched")
class TheProductionRateLimiterIsUntouchedTest {

    private static final Path PRODUCTION_PROPERTIES =
            Path.of("src/main/resources/application.properties");
    private static final Path TEST_OVERLAY =
            Path.of("src/test/resources/config/application.properties");

    @Nested
    @DisplayName("the configuration that ships")
    class TheShippedConfiguration {

        @Test
        @DisplayName("still throttles auth at 20 a minute by default")
        void productionAuthLimitIsUnchanged() throws IOException {
            String production = Files.readString(PRODUCTION_PROPERTIES);
            assertTrue(
                    production.contains("rate-limit.auth-per-minute=${RATE_LIMIT_AUTH_PER_MINUTE:20}"),
                    "production's auth limit is no longer 20. If that was deliberate, change this "
                            + "test in the same commit and say why in the message - but if it "
                            + "happened while making a test go green, it is a password-guessing "
                            + "budget raised by accident.");
        }

        @Test
        @DisplayName("keeps every other limiter bucket at a real number too")
        void theOtherBucketsAreStillReal() throws IOException {
            String production = Files.readString(PRODUCTION_PROPERTIES);
            for (String bucket : List.of(
                    "rate-limit.checkout-per-minute=${RATE_LIMIT_CHECKOUT_PER_MINUTE:20}",
                    "rate-limit.mutation-per-minute=${RATE_LIMIT_MUTATION_PER_MINUTE:60}",
                    "rate-limit.admin-per-minute=${RATE_LIMIT_ADMIN_PER_MINUTE:30}")) {
                assertTrue(production.contains(bucket),
                        "production no longer declares " + bucket);
            }
        }

        @Test
        @DisplayName("the raise lives in the test overlay, and only there")
        void theRaiseIsTestOnly() throws IOException {
            String overlay = Files.readString(TEST_OVERLAY);
            assertTrue(overlay.contains("rate-limit.auth-per-minute="),
                    "the test overlay no longer raises the auth limit - if that was removed, the "
                            + "suite is back to competing for twenty requests a minute and will "
                            + "start failing whichever class is unlucky");

            // The overlay merges into production configuration; it must not be
            // the file that production reads.
            assertTrue(overlay.contains("classpath:/config/") || overlay.contains("MERGES"),
                    "this overlay works because it lives in classpath:/config/ and merges. If it "
                            + "moves, it shadows the datasource and everything else.");
        }
    }

    @Nested
    @DisplayName("the filter itself")
    class TheFilter {

        /**
         * Still refuses past the limit it is given.
         *
         * <p>The point of the whole exercise: the property moved, the
         * behaviour did not. Built the way RateLimitFilterTest builds it -
         * direct construction with explicit numbers - which is also why no
         * property overlay can reach it.
         */
        @Test
        @DisplayName("returns 429 once the configured number is exceeded")
        void theLimiterStillRefuses() throws Exception {
            AtomicLong calls = new AtomicLong();
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            when(redis.execute(any(RedisScript.class), anyList(), any()))
                    .thenAnswer(invocation -> calls.incrementAndGet());

            // Two auth requests a minute, and then no more.
            RateLimitFilter filter = new RateLimitFilter(redis, false, 2, 2, 60, 2, 60);

            assertEquals(200, statusAfterAuthRequest(filter));
            assertEquals(200, statusAfterAuthRequest(filter));
            assertEquals(429, statusAfterAuthRequest(filter),
                    "the third auth request inside the window was allowed - the limiter is not "
                            + "refusing, whatever the configuration says");
        }

        private int statusAfterAuthRequest(RateLimitFilter filter) throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
            request.setRemoteAddr("203.0.113.9");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            return response.getStatus();
        }
    }

    @Test
    @DisplayName("control: the production file is where this test thinks it is")
    void theProductionFileIsReadable() throws IOException {
        assertNotNull(Files.readString(PRODUCTION_PROPERTIES));
        assertTrue(Files.readString(PRODUCTION_PROPERTIES).contains("rate-limit."),
                "read a file with no rate-limit settings in it at all - the path is wrong and "
                        + "every assertion above is passing on the wrong file");
    }
}
