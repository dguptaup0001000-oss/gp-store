package com.gpstore.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Browser/CDN cache for public catalogue GETs, and no-store everywhere else.
 *
 * Spring Security otherwise stamps every response with no-store. Catalog
 * rows already live in Caffeine L1 (15s) and Redis L2; repeating the same
 * JSON across thousands of shoppers is wasted CPU and bandwidth. max-age=15
 * matches L1 so a phone that reopens the feed does not hit Tomcat at all
 * for a quarter-minute, without serving a stale price for long after an
 * admin edit (evict + 15s ceiling).
 *
 * Never applied to:
 * - search (query-specific, currently also the expensive path)
 * - admin / writes
 * - auth, checkout, orders, payments, inventory
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 20)
public class CatalogPublicCacheFilter extends OncePerRequestFilter {

    static final String PUBLIC_CATALOG =
            "public, max-age=15, stale-while-revalidate=45";
    static final String NO_STORE = "no-store";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (isPublicCatalogGet(request)) {
            response.setHeader("Cache-Control", PUBLIC_CATALOG);
        } else {
            response.setHeader("Cache-Control", NO_STORE);
        }
        filterChain.doFilter(request, response);
    }

    static boolean isPublicCatalogGet(HttpServletRequest request) {
        if (!HttpMethod.GET.matches(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        if (path.contains("/admin") || path.contains("/search")) {
            return false;
        }
        return path.contains("/api/products") || path.contains("/api/categories");
    }
}
