package com.gpstore.platform;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Puts a tenant scope on the thread for the length of one request, and takes
 * it off again.
 *
 * THE FINALLY BLOCK IS THE SECURITY CONTROL. Tomcat hands the same thread to
 * the next request; a scope left behind is the scope that request starts
 * with, which is one shop reading another's data through no fault of any
 * query. That is why the clear is unconditional and why this filter does
 * nothing else - a filter with one job has one place to get it wrong.
 *
 * RUNS AFTER AUTHENTICATION, because the scope comes from the credential and
 * there is no credential until JwtFilter has run. Placed after RateLimitFilter
 * for the same reason the rate limiter is placed after JwtFilter: work that
 * a request will be refused for should be refused before anything reads a
 * database to set up for it.
 *
 * RESOLUTION FAILURE IS NOT A 500. A credential that cannot be resolved to a
 * shop is an authorization problem, and answering 403 says so without leaking
 * whether the shop exists. It also keeps a misconfigured deployment from
 * looking like a broken one.
 */
@Component
public class TenantContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantContextFilter.class);

    private final TenantResolver resolver;

    public TenantContextFilter(TenantResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            TenantContext.set(resolver.resolve());
        } catch (RuntimeException cannotResolve) {
            // No shop id in the message. Whether a given shop exists is not
            // something an unauthenticated caller should be able to learn
            // from an error string.
            log.warn("Refusing a request whose credential resolves to no shop: {}",
                    cannotResolve.getClass().getSimpleName());
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "This account is not associated with a shop.");
            return;
        }

        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Endpoints that exist before, or outside, any shop.
     *
     * Health, version and the actuator are how a load balancer and a deploy
     * script decide whether the application is alive; making them depend on a
     * shop row being present would mean a database problem reads as a dead
     * process. Auth endpoints run before there is a credential to resolve
     * from, and the payment webhook arrives from Cashfree with no session at
     * all - it carries a signature instead, which is verified elsewhere.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.startsWith("/api/health")
                || path.startsWith("/api/version")
                || path.startsWith("/actuator")
                || path.startsWith("/api/auth/")
                || path.startsWith("/api/payments/webhooks/");
    }
}
