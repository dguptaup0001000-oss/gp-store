package com.gpstore.config;

import com.gpstore.security.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Brute-force and abuse protection on the endpoints worth protecting.
 *
 * Redis-backed (a fixed-window counter via one atomic INCR+EXPIRE) so every
 * backend instance shares the same count - an in-memory counter would make
 * the real limit (limit x number of instances) the moment more than one
 * instance runs.
 *
 * TWO FAILURE MODES, on purpose:
 *
 *   AUTH / CHECKOUT / ADMIN  -> fail CLOSED via a local in-memory window.
 *       A Redis outage must not silently disable brute-force protection on
 *       login, OTP, payments or admin writes. The local limiter is per-JVM
 *       so the real ceiling under an outage is (limit × instances), which
 *       is still a ceiling. If even the local limiter throws, the request
 *       is rejected with 429 rather than allowed through.
 *
 *   SEARCH / MUTATION        -> fail OPEN.
 *       Instant search and cart writes are availability-critical. A Redis
 *       blip must not take the shop offline; scraping during an outage is
 *       the lesser harm. Catalog browsing is not limited at all.
 *
 * IDENTITY STRATEGY - the important part, and what changed.
 *
 * Everything used to be keyed on IP alone at 10 requests/minute. On Indian
 * mobile networks that is actively wrong: carrier-grade NAT puts thousands
 * of unrelated subscribers behind one public IP, so ten customers checking
 * out from the same carrier could lock out every other customer on that
 * carrier. The limit measured "how busy is this IP", which for mobile
 * traffic is not a meaningful statement about any individual user.
 *
 * So the key now depends on what identity is actually available:
 *
 *   AUTHENTICATED endpoints -> keyed by customer id.
 *     Precise, immune to NAT, and directly meaningful: it caps what one
 *     account can do, which is the thing worth capping. A shared carrier IP
 *     no longer causes collateral damage.
 *
 *   UNAUTHENTICATED (login/register/OTP) -> keyed by IP, necessarily.
 *     There is no verified identity yet - that is the point of these
 *     endpoints - so IP is the only signal available, and IP protection is
 *     what actually stops credential stuffing. These keep a deliberately
 *     tighter limit. The NAT trade-off is real and unavoidable here: the
 *     alternative (no limit) means anyone can brute-force passwords and burn
 *     SMS credits on OTP sends. The limit is configurable so it can be
 *     raised if legitimate users on one carrier are being blocked.
 *
 * DOCUMENTED LIMITS (all per minute, all overridable by env var):
 *
 *   rate-limit.auth-per-minute            default 20, per IP
 *       login, register, otp/send, otp/verify, otp/login/*,
 *       password-reset/*, reset-password-with-otp.
 *       Tight on purpose - these are the credential-stuffing and
 *       SMS-cost targets. 20/min still allows a real person to mistype a
 *       password several times and request a fresh OTP.
 *
 *   rate-limit.checkout-per-minute        default 20, per customer
 *       orders/place, POST /api/payments, checkout-session, payment
 *       verify, refund/COD/UPI confirm writes, coupon validate.
 *       No human places 20 orders a minute, so this only ever
 *       catches a stuck retry loop or a script, while leaving impatient
 *       double-taps (already made safe by idempotency keys and row locks)
 *       comfortably under the line. Checkout-session, verify, and other
 *       per-order payment writes share one normalised path so hammering
 *       many order ids does not multiply the quota. Coupon validate is
 *       here so brute-forcing offer codes is capped the same way.
 *
 *   rate-limit.admin-per-minute             default 30, per admin account
 *       POST /api/notifications/broadcast and writes under /api/admin/**.
 *       Broadcasts can fan out to every customer; this is a brake, not the
 *       RBAC check (that stays in SecurityConfig).
 *
 *   rate-limit.search-per-minute            default 60, per customer or IP
 *       GET /api/products/search*. Instant search is the expensive read.
 *       60/min still allows a person typing with debounce; it stops a
 *       scrape. Authenticated callers are keyed by customer id so CGNAT
 *       does not share one quota across unrelated shoppers.
 *
 * Refresh and logout sit in AUTH (same 20/min as login) because a stolen
 * refresh token used as a hammer is the same class of abuse as login
 * stuffing. They are IP-keyed like the rest of AUTH: the refresh endpoint
 * is unauthenticated (the body carries the token).
 *
 * None of this replaces the correctness guards (row locks, unique
 * constraints, idempotency keys) - those are what make concurrent requests
 * safe. Rate limiting only stops one client burning capacity that other
 * shoppers need.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

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
    private final int authPerMinute;
    private final int checkoutPerMinute;
    private final int mutationPerMinute;
    private final int adminPerMinute;
    private final int searchPerMinute;
    private final LocalFixedWindowRateLimiter localLimiter =
            new LocalFixedWindowRateLimiter(TimeUnit.SECONDS.toMillis(WINDOW_SECONDS));

    public RateLimitFilter(
            StringRedisTemplate redisTemplate,
            @Value("${rate-limit.trust-forwarded-for:false}") boolean trustForwardedFor,
            @Value("${rate-limit.auth-per-minute:20}") int authPerMinute,
            @Value("${rate-limit.checkout-per-minute:20}") int checkoutPerMinute,
            @Value("${rate-limit.mutation-per-minute:60}") int mutationPerMinute,
            @Value("${rate-limit.admin-per-minute:30}") int adminPerMinute,
            @Value("${rate-limit.search-per-minute:60}") int searchPerMinute) {
        this.redisTemplate = redisTemplate;
        this.trustForwardedFor = trustForwardedFor;
        this.authPerMinute = authPerMinute;
        this.checkoutPerMinute = checkoutPerMinute;
        this.mutationPerMinute = mutationPerMinute;
        this.adminPerMinute = adminPerMinute;
        this.searchPerMinute = searchPerMinute;
    }

    /** Which bucket a request falls into, or null if it is not limited at all. */
    enum Bucket { AUTH, CHECKOUT, MUTATION, ADMIN, SEARCH }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        Bucket bucket = classify(request);
        if (bucket == null) {
            filterChain.doFilter(request, response);
            return;
        }

        int limit = switch (bucket) {
            case AUTH -> authPerMinute;
            case CHECKOUT -> checkoutPerMinute;
            case MUTATION -> mutationPerMinute;
            case ADMIN -> adminPerMinute;
            case SEARCH -> searchPerMinute;
        };

        String clientKey = "ratelimit:" + identity(bucket, request) + ":" + limitPath(request.getServletPath());

        if (!allow(bucket, clientKey, limit)) {
            writeTooManyRequests(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Redis first. Security buckets fall back to a local window. Availability
     * buckets (search, cart writes) fail open so a Redis blip does not take
     * the shop down.
     */
    private boolean allow(Bucket bucket, String clientKey, int limit) {
        try {
            Long count = redisTemplate.execute(
                    INCREMENT_AND_EXPIRE, List.of(clientKey), String.valueOf(WINDOW_SECONDS));
            return count == null || count <= limit;
        } catch (Exception ex) {
            if (failsOpen(bucket)) {
                log.warn("Rate limiter unavailable (Redis unreachable?) - allowing {} through: {}",
                        bucket, ex.getMessage());
                return true;
            }
            log.warn("Rate limiter unavailable (Redis unreachable?) - using local fallback for {}: {}",
                    bucket, ex.getMessage());
            try {
                return localLimiter.allow(clientKey, limit);
            } catch (Exception localEx) {
                log.error("Local security rate limiter failed - rejecting {}: {}", bucket, localEx.getMessage());
                return false;
            }
        }
    }

    private static boolean failsOpen(Bucket bucket) {
        return bucket == Bucket.SEARCH || bucket == Bucket.MUTATION;
    }

    private static void writeTooManyRequests(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"error\":\"Too Many Requests\",\"message\":\"Too many attempts - please wait a minute and try again.\"}");
    }

    Bucket classify(HttpServletRequest request) {
        String path = request.getServletPath();
        String method = request.getMethod();

        if (path.equals("/api/auth/login")
                || path.equals("/api/auth/register")
                || path.equals("/api/auth/otp/send")
                || path.equals("/api/auth/otp/verify")
                || path.equals("/api/auth/otp/login/request")
                || path.equals("/api/auth/otp/login/verify")
                || path.equals("/api/auth/password-reset/request")
                || path.equals("/api/auth/password-reset/verify")
                || path.equals("/api/auth/password-reset/complete")
                || path.equals("/api/auth/reset-password-with-otp")
                || path.equals("/api/auth/refresh")
                || path.equals("/api/auth/logout")
                || path.equals("/api/auth/logout-all")
                || path.equals("/api/auth/change-password")) {
            return Bucket.AUTH;
        }

        if (path.startsWith("/api/products/search")) {
            return Bucket.SEARCH;
        }

        if (path.equals("/api/orders/place")
                || path.equals("/api/payments")
                || path.equals("/api/coupons/validate")
                || isCheckoutSession(path)
                || isPaymentVerify(path)
                || isPaymentSensitiveWrite(path)) {
            return Bucket.CHECKOUT;
        }

        // Only mutations - browsing a cart or reading reviews is not abuse,
        // and rate-limiting reads would break normal scrolling.
        boolean isWrite = method.equals("POST") || method.equals("PUT")
                || method.equals("PATCH") || method.equals("DELETE");
        if (isWrite && (path.startsWith("/api/carts")
                || path.startsWith("/api/cart-items")
                || path.startsWith("/api/reviews"))) {
            return Bucket.MUTATION;
        }

        if (isWrite && (path.equals("/api/notifications/broadcast")
                || path.startsWith("/api/admin/"))) {
            return Bucket.ADMIN;
        }

        return null;
    }

    static boolean isCheckoutSession(String path) {
        return path != null && path.matches("/api/payments/order/\\d+/checkout-session");
    }

    static boolean isPaymentVerify(String path) {
        return path != null && path.matches("/api/payments/order/\\d+/verify");
    }

    static boolean isPaymentSensitiveWrite(String path) {
        return path != null && path.matches(
                "/api/payments/order/\\d+/(refund|refund/complete|cod/complete|upi/confirm)");
    }

    /**
     * Collapses per-order payment paths so one customer cannot multiply
     * their quota by rotating order ids.
     */
    static String limitPath(String path) {
        if (isCheckoutSession(path)) {
            return "/api/payments/order/*/checkout-session";
        }
        if (isPaymentVerify(path)) {
            return "/api/payments/order/*/verify";
        }
        if (isPaymentSensitiveWrite(path)) {
            return path.replaceFirst("/api/payments/order/\\d+/", "/api/payments/order/*/");
        }
        return path;
    }

    /**
     * Customer id when the request is authenticated, IP otherwise.
     *
     * This filter is registered to run AFTER JwtFilter (see SecurityConfig)
     * precisely so the SecurityContext is already populated here - the two
     * used to be registered at the same position with the rate limiter
     * first, which meant no identity was ever available and IP was the only
     * possible key.
     *
     * AUTH-bucket requests are always keyed by IP even if a token happens to
     * be present: someone brute-forcing logins while holding a valid token
     * for a different account must not get a fresh quota per account.
     *
     * SEARCH is the exception among public GETs: an authenticated shopper
     * is keyed by customer id so a shared carrier IP does not starve
     * everyone else's search box.
     */
    private String identity(Bucket bucket, HttpServletRequest request) {
        if (bucket != Bucket.AUTH) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user
                    && user.getCustomerId() != null) {
                return "cust:" + user.getCustomerId();
            }
        }
        return "ip:" + clientIp(request);
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
