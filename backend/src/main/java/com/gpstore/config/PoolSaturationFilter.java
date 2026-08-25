package com.gpstore.config;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fast 503 when the database pool is actually exhausted, instead of letting
 * this request occupy a Tomcat thread for the 5s Hikari timeout and then
 * timing out at Render as a 502.
 *
 * Waiting threads alone are not enough: a brief GC or one slow query can
 * queue a few waiters while most connections are still idle. Shed only when
 * waiters have piled up AND every connection is already in use.
 *
 * Only GET catalog paths: checkout must still queue for a connection or it
 * would shed paying customers. Health probes skip this so liveness stays up.
 *
 * A 503 from this filter is intentional load shedding. A 502 is not - that
 * comes from the proxy when this process never answered. Do not treat them
 * as the same failure.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class PoolSaturationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PoolSaturationFilter.class);

    static final int WAITING_SHED_THRESHOLD = 4;

    private static final long SHED_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final DataSource dataSource;
    private final int waitingThreshold;
    private final Counter shedCounter;
    private final AtomicLong lastShedLogNanos = new AtomicLong();

    public PoolSaturationFilter(
            DataSource dataSource,
            @Value("${pool.saturation.waiting-threshold:4}") int waitingThreshold,
            MeterRegistry meterRegistry) {
        this.dataSource = dataSource;
        this.waitingThreshold = Math.max(1, waitingThreshold);
        this.shedCounter = meterRegistry.counter("pool.shed.catalog");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!HttpMethod.GET.matches(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        if (path.contains("/api/health") || path.contains("/actuator")) {
            return true;
        }
        return !path.contains("/api/products") && !path.contains("/api/categories");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (isSaturated()) {
            shedCounter.increment();
            logShed(request);
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setHeader("Retry-After", "1");
            response.setHeader("X-GP-Shed", "pool-saturated");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"The shop is busy. Please try again in a moment.\",\"cause\":\"POOL_SATURATED\"}");
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
                "Catalog GET {} shed with HTTP 503 (intentional pool saturation, not a 502). "
                        + "Further sheds in this window are counted as pool.shed.catalog.",
                request.getRequestURI());
    }

    private boolean isSaturated() {
        try {
            HikariDataSource hikari = dataSource instanceof HikariDataSource h
                    ? h
                    : dataSource.unwrap(HikariDataSource.class);
            HikariPoolMXBean mx = hikari.getHikariPoolMXBean();
            if (mx == null) {
                return false;
            }
            return mx.getThreadsAwaitingConnection() >= waitingThreshold
                    && mx.getActiveConnections() >= hikari.getMaximumPoolSize();
        } catch (SQLException | RuntimeException ignored) {
            return false;
        }
    }
}
