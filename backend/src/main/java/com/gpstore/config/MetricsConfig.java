package com.gpstore.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Turns on method-level timing metrics.
 *
 * Why metrics rather than logging: the obvious way to answer "why does
 * checkout feel slow" is to log a duration per request, and that is exactly
 * what should NOT ship. At real traffic a per-request INFO line is its own
 * cost - log volume, IO, and storage - and it still only answers the
 * question for whoever happens to read the line. A timer answers it for
 * every request at once, with percentiles, and costs a counter increment.
 *
 * Spring Boot already auto-instruments HTTP requests as http.server.requests
 * (tagged by uri, method and status), so endpoint-level timing needs no code
 * at all - see application.properties for the percentile configuration that
 * makes p95/p99 available rather than just a mean, which is the number that
 * actually matters when the complaint is "sometimes it takes 8 seconds".
 *
 * This bean adds the missing half: @Timed on individual service methods, so
 * a slow endpoint can be attributed to the specific operation inside it
 * instead of guessing. Both are scraped through the existing
 * /v1/actuator/prometheus endpoint - no new infrastructure.
 */
@Configuration
public class MetricsConfig {

    /**
     * Required for @Timed to do anything on plain Spring beans - without it
     * the annotation is silently inert, which is a worse failure than not
     * having it at all because the metric simply never appears and nothing
     * says why.
     */
    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }
}
