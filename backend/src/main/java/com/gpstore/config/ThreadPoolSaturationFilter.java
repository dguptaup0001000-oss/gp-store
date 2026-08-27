package com.gpstore.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fast 503 when every Tomcat catalog worker is already busy.
 *
 * This is not a rate limit and is not there to make a load test look green.
 * Eighty threads on 2 vCPU cannot run 2,000 in-flight JSON responses; they
 * can only queue them until clients time out. Returning 503 with Retry-After
 * frees the worker in milliseconds so legitimate traffic still moves, and
 * the client can tell overload from a bug.
 *
 * Health, actuator, checkout, and writes skip this filter.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ThreadPoolSaturationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ThreadPoolSaturationFilter.class);

    private static final long SHED_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final TomcatRequestCapacity capacity;
    private final Counter shedCounter;
    private final AtomicLong lastShedLogNanos = new AtomicLong();

    public ThreadPoolSaturationFilter(
            TomcatRequestCapacity capacity,
            MeterRegistry meterRegistry) {
        this.capacity = capacity;
        this.shedCounter = meterRegistry.counter("tomcat.shed.catalog");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!HttpMethod.GET.matches(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        if (path.contains("/api/health") || path.contains("/actuator") || path.contains("/api/version")) {
            return true;
        }
        return !path.contains("/api/products") && !path.contains("/api/categories");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (capacity.isMainPoolSaturated()) {
            shedCounter.increment();
            logShed(request);
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setHeader("Retry-After", "1");
            response.setHeader("X-GP-Shed", "thread-pool-saturated");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                    "{\"error\":\"The shop is busy. Please try again in a moment.\",\"cause\":\"THREAD_POOL_SATURATED\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void logShed(HttpServletRequest request) {
        long now = System.nanoTime();
        long previous = lastShedLogNanos.get();
        if (previous != 0 && now - previous < SHED_LOG_INTERVAL_NANOS) {
            return;
        }
        lastShedLogNanos.set(now);
        log.warn(
                "Catalog GET {} shed with HTTP 503 (Tomcat workers saturated, not a 502). "
                        + "Further sheds in this window are counted as tomcat.shed.catalog.",
                request.getRequestURI());
    }
}
