package com.gpstore.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Basic brute-force protection on login/register/checkout - the most
 * obvious targets for a credential-stuffing, account-spam, or checkout-spam
 * script. Limits each IP to MAX_REQUESTS per WINDOW_SECONDS on these
 * specific endpoints only - everything else is untouched.
 *
 * Redis-backed (a fixed-window counter via one atomic INCR+EXPIRE, not the
 * sliding-window deque the previous in-memory version used - a fixed window
 * is the standard distributed-rate-limit pattern and close enough here;
 * exact sliding-window precision was never the point) so every backend
 * instance shares the same count. The previous ConcurrentHashMap-based
 * version tracked counts per-instance, meaning the real effective limit
 * became (MAX_REQUESTS x number of instances) the moment more than one
 * instance ran.
 *
 * Fails OPEN (lets the request through) if Redis itself is unreachable -
 * a Redis outage should degrade rate-limiting, not take down login/checkout
 * entirely.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final int MAX_REQUESTS = 10;
    private static final long WINDOW_SECONDS = 60;

    private static final DefaultRedisScript<Long> INCREMENT_AND_EXPIRE = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final boolean trustForwardedFor;

    public RateLimitFilter(
            StringRedisTemplate redisTemplate,
            @Value("${rate-limit.trust-forwarded-for:false}") boolean trustForwardedFor) {
        this.redisTemplate = redisTemplate;
        this.trustForwardedFor = trustForwardedFor;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getServletPath();
        boolean isRateLimited = path.equals("/api/auth/login")
                || path.equals("/api/auth/register")
                || path.equals("/api/auth/otp/send")
                || path.equals("/api/auth/otp/verify")
                // Order placement and payment initiation are the two write
                // paths a spike hammers hardest - impatient double-taps and
                // mobile client retry-on-timeout both replay the same
                // request. This doesn't replace the DB-level correctness
                // guards (row locks, unique constraints) already in place
                // for those - it just stops one client from burning
                // capacity that legitimate concurrent shoppers need.
                || path.equals("/api/orders/place")
                || path.equals("/api/payments");

        if (!isRateLimited) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientKey = "ratelimit:" + clientIp(request) + ":" + path;

        Long count;
        try {
            count = redisTemplate.execute(
                    INCREMENT_AND_EXPIRE, List.of(clientKey), String.valueOf(WINDOW_SECONDS));
        } catch (Exception ex) {
            log.warn("Rate limiter unavailable (Redis unreachable?) - allowing request through: {}", ex.getMessage());
            filterChain.doFilter(request, response);
            return;
        }

        if (count != null && count > MAX_REQUESTS) {
            response.setStatus(429); // 429 Too Many Requests
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"Too Many Requests\",\"message\":\"Too many attempts - please wait a minute and try again.\"}");
            return;
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
