package com.gpstore.presence;

import com.gpstore.security.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Stamps "this account was here" on every authenticated request.
 *
 * Runs AFTER the response is produced, not before, and never fails the
 * request: presence is telemetry for a dashboard, and a customer's checkout
 * must not depend on it. {@link PresenceTracker#recordSeen} swallows its own
 * Redis errors for the same reason; the try/finally here covers the rest.
 *
 * Health and actuator traffic is skipped. Without that, an uptime monitor
 * hitting /api/health every ten seconds with a service account would show up
 * as a permanently-online "shopper" and the dashboard would never read zero.
 */
@Component
public class PresenceFilter extends OncePerRequestFilter {

    private final PresenceTracker tracker;

    public PresenceFilter(PresenceTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null
                || path.contains("/api/health")
                || path.contains("/actuator");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null
                    && authentication.getPrincipal() instanceof AuthenticatedUser user
                    && user.getCustomerId() != null) {
                tracker.recordSeen(user.getCustomerId());
            }
        }
    }
}
