package com.gpstore.config;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class PoolSaturationFilter extends OncePerRequestFilter {

    static final int WAITING_SHED_THRESHOLD = 4;

    private final DataSource dataSource;
    private final int waitingThreshold;

    public PoolSaturationFilter(
            DataSource dataSource,
            @Value("${pool.saturation.waiting-threshold:4}") int waitingThreshold) {
        this.dataSource = dataSource;
        this.waitingThreshold = Math.max(1, waitingThreshold);
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
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setHeader("Retry-After", "1");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"The shop is busy. Please try again in a moment.\"}");
            return;
        }
        filterChain.doFilter(request, response);
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
