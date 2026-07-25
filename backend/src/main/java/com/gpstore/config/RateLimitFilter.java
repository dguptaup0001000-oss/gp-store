package com.gpstore.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Basic brute-force protection on login/register - the most obvious target
 * for a credential-stuffing or account-spam script, and currently had zero
 * protection at all. Limits each IP to MAX_REQUESTS per WINDOW_SECONDS on
 * these specific endpoints only - everything else is untouched.
 *
 * HONEST LIMITATION: this is in-memory, per-instance. It's the right choice
 * for a single-instance deployment (your current setup), but if you ever run
 * multiple backend instances behind a load balancer, each instance tracks its
 * own counts independently, so the real effective limit becomes
 * (MAX_REQUESTS x number of instances). Real multi-instance rate limiting
 * needs a shared store (Redis) - same "swap later" pattern as caching.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_REQUESTS = 10;
    private static final long WINDOW_SECONDS = 60;

    private final ConcurrentHashMap<String, Deque<Instant>> requestLog = new ConcurrentHashMap<>();

    private final boolean trustForwardedFor;

    public RateLimitFilter(@org.springframework.beans.factory.annotation.Value("${rate-limit.trust-forwarded-for:false}") boolean trustForwardedFor) {
        this.trustForwardedFor = trustForwardedFor;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getServletPath();
        boolean isRateLimited = path.equals("/api/auth/login") || path.equals("/api/auth/register");

        if (!isRateLimited) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientKey = clientIp(request) + ":" + path;
        Instant now = Instant.now();
        Instant windowStart = now.minusSeconds(WINDOW_SECONDS);

        Deque<Instant> timestamps = requestLog.computeIfAbsent(clientKey, k -> new ConcurrentLinkedDeque<>());

        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(windowStart)) {
                timestamps.pollFirst();
            }

            if (timestamps.size() >= MAX_REQUESTS) {
                response.setStatus(429); // 429 Too Many Requests
                response.setContentType("application/json");
                response.getWriter().write(
                        "{\"error\":\"Too Many Requests\",\"message\":\"Too many attempts - please wait a minute and try again.\"}");
                return;
            }

            timestamps.addLast(now);
        }

        filterChain.doFilter(request, response);
    }

    private String clientIp(HttpServletRequest request) {
        // X-Forwarded-For is trivially spoofable by the client unless a real
        // proxy/load balancer in front of this app is overwriting it - trusting
        // it blindly would let an attacker fake a new IP on every request and
        // bypass the whole rate limit. Only enable rate-limit.trust-forwarded-for
        // once you've confirmed you're actually behind a proxy that sets it
        // (e.g. a managed platform's load balancer).
        if (trustForwardedFor) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
