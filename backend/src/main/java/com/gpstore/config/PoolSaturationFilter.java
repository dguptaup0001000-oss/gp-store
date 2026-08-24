package com.gpstore.config;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
 * Fast 503 when the database pool already has a queue, instead of letting
 * this request occupy a Tomcat thread for the 5s Hikari timeout and then
 * timing out at Render as a 502.
 *
 * Only GET catalog paths: checkout must still queue for a connection or it
 * would shed paying customers. Health probes skip this so liveness stays up.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class PoolSaturationFilter extends OncePerRequestFilter {

    static final int WAITING_SHED_THRESHOLD = 4;

    private final DataSource dataSource;

    public PoolSaturationFilter(DataSource dataSource) {
        this.dataSource = dataSource;
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
        HikariPoolMXBean mx = poolMx();
        if (mx != null && mx.getThreadsAwaitingConnection() >= WAITING_SHED_THRESHOLD) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setHeader("Retry-After", "1");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"The shop is busy. Please try again in a moment.\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private HikariPoolMXBean poolMx() {
        try {
            HikariDataSource hikari = dataSource instanceof HikariDataSource h
                    ? h
                    : dataSource.unwrap(HikariDataSource.class);
            return hikari.getHikariPoolMXBean();
        } catch (SQLException | RuntimeException ignored) {
            return null;
        }
    }
}
