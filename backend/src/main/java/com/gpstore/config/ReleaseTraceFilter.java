package com.gpstore.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Proves which mobile build reached which backend binary without logging any
 * token, body, query value or customer identifier. The response header is
 * also visible in the APK's device-local diagnostics screen.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ReleaseTraceFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ReleaseTraceFilter.class);
    private static final Set<String> TRACED_PREFIXES = Set.of(
            "/api/marketplace", "/api/wishlists", "/api/notifications",
            "/api/shop/products", "/api/platform/control/customers",
            "/api/version");

    private final AppBuildInfo buildInfo;

    public ReleaseTraceFilter(AppBuildInfo buildInfo) {
        this.buildInfo = buildInfo;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        response.setHeader("X-GP-Store-Backend-Build", buildInfo.binaryGitCommit());
        try {
            filterChain.doFilter(request, response);
        } finally {
            String path = request.getRequestURI().substring(
                    Math.min(request.getContextPath().length(), request.getRequestURI().length()));
            if (TRACED_PREFIXES.stream().anyMatch(path::startsWith)) {
                log.info("release_path method={} path={} status={} clientApp={} clientBuild={} backendBuild={}",
                        request.getMethod(), path, response.getStatus(),
                        safeHeader(request, "X-GP-Store-Client-App"),
                        safeHeader(request, "X-GP-Store-Client-Build"),
                        buildInfo.binaryGitCommit());
            }
        }
    }

    private static String safeHeader(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,64}")) {
            return "unknown";
        }
        return value;
    }
}
