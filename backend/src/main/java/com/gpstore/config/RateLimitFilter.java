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
   *       orders/place, GET checkout-preview, POST /api/payments, checkout-session,
   *       payment verify, refund/COD/UPI confirm writes, coupon validate.
   *       Preview is here because a coupon code on the query string is the
   *       same brute-force surface as /coupons/validate.
   *
 *   rate-limit.admin-per-minute             default 30, per admin/worker account
 *       POST /api/notifications/broadcast, writes under /api/admin/**,
 *       and writes under /api/worker/**, /api/deliveries, /api/inventory,
 *       /api/products, /api/categories, /api/product-variants,
 *       and /api/orders (except place, which is CHECKOUT).
 *
 *   rate-limit.upload-per-minute            default 240, per admin account, fail-closed
 *       writes under /api/uploads/**. ADMIN at 30/min made each photo
 *       (sign + confirm) cost 2 of 30, ~15 images/min. 240/min is 120
 *       images/min; batch sign/confirm of 20 keys is 20 images / 2 requests.
 *
 *   rate-limit.webhook-per-minute           default 300, per IP, fail-closed
 *       POST /api/payments/webhooks/**. Separate from ADMIN so a Cashfree
 *       retry burst does not share the 30/min admin write quota.
   *       DELETE /api/customers/me and PUT /api/customers/me are AUTH
       (account destruction and phone-number rebind).
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
    private final ClientIpResolver clientIpResolver;
    private final int authPerMinute;
    private final int checkoutPerMinute;
    private final int mutationPerMinute;
    private final int adminPerMinute;
    private final int searchPerMinute;
    private final int webhookPerMinute;
    private final int uploadPerMinute;
    private final LocalFixedWindowRateLimiter localLimiter =
            new LocalFixedWindowRateLimiter(TimeUnit.SECONDS.toMillis(WINDOW_SECONDS));

    RateLimitFilter(
            StringRedisTemplate redisTemplate,
            boolean trustForwardedFor,
            int authPerMinute,
            int checkoutPerMinute,
            int mutationPerMinute,
            int adminPerMinute,
            int searchPerMinute) {
        this(redisTemplate,
                new ClientIpResolver(trustForwardedFor, ClientIpResolver.DEFAULT_TRUSTED_CIDRS),
                authPerMinute, checkoutPerMinute, mutationPerMinute, adminPerMinute, searchPerMinute,
                300, 240);
    }

    RateLimitFilter(
            StringRedisTemplate redisTemplate,
            ClientIpResolver clientIpResolver,
            int authPerMinute,
            int checkoutPerMinute,
            int mutationPerMinute,
            int adminPerMinute,
            int searchPerMinute,
            int webhookPerMinute) {
        this(redisTemplate, clientIpResolver, authPerMinute, checkoutPerMinute, mutationPerMinute,
                adminPerMinute, searchPerMinute, webhookPerMinute, 240);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RateLimitFilter(
            StringRedisTemplate redisTemplate,
            ClientIpResolver clientIpResolver,
            @Value("${rate-limit.auth-per-minute:20}") int authPerMinute,
            @Value("${rate-limit.checkout-per-minute:20}") int checkoutPerMinute,
            @Value("${rate-limit.mutation-per-minute:60}") int mutationPerMinute,
            @Value("${rate-limit.admin-per-minute:30}") int adminPerMinute,
            @Value("${rate-limit.search-per-minute:60}") int searchPerMinute,
            @Value("${rate-limit.webhook-per-minute:300}") int webhookPerMinute,
            @Value("${rate-limit.upload-per-minute:240}") int uploadPerMinute) {
        this.redisTemplate = redisTemplate;
        this.clientIpResolver = clientIpResolver;
        this.authPerMinute = authPerMinute;
        this.checkoutPerMinute = checkoutPerMinute;
        this.mutationPerMinute = mutationPerMinute;
        this.adminPerMinute = adminPerMinute;
        this.searchPerMinute = searchPerMinute;
        this.webhookPerMinute = webhookPerMinute;
        this.uploadPerMinute = uploadPerMinute;
    }

    /** Which bucket a request falls into, or null if it is not limited at all. */
    enum Bucket { AUTH, CHECKOUT, MUTATION, ADMIN, SEARCH, WEBHOOK, UPLOAD }

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
            case WEBHOOK -> webhookPerMinute;
            case UPLOAD -> uploadPerMinute;
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
                || path.equals("/api/auth/change-password")
                // THE WORKER APP'S FRONT DOOR. It is a public credential
                // endpoint like every other line here, and it only ever
                // matched by accident before - as a write under /api/worker/,
                // which put it in the looser ADMIN bucket. Password guessing
                // does not care which app it is aimed at.
                || path.equals("/api/worker/auth/login")) {
            return Bucket.AUTH;
        }

        if (path.startsWith("/api/products/search")) {
            return Bucket.SEARCH;
        }

        // THE ONLY ENDPOINT THAT SPENDS SOMEBODY ELSE'S QUOTA. Every call here
        // may reach OpenStreetMap, whose fair-use policy we are guests under.
        // The geocoder has its own global one-a-second gate, so a flood is
        // already cheap for them - this keeps it cheap for us too. SEARCH
        // because it fails OPEN: a convenience pre-fill must never be the
        // reason somebody cannot save an address.
        if (path.equals("/api/addresses/reverse-geocode")) {
            return Bucket.SEARCH;
        }

        if (path.equals("/api/orders/place")
                || path.equals("/api/orders/checkout-preview")
                || path.equals("/api/payments")
                || path.equals("/api/coupons/validate")
                || isCheckoutSession(path)
                || isPaymentVerify(path)
                || isPaymentSensitiveWrite(path)) {
            return Bucket.CHECKOUT;
        }

        if (path.equals("/api/customers/me") && (method.equals("DELETE") || method.equals("PUT"))) {
            return Bucket.AUTH;
        }

        // Only mutations - browsing a cart or reading reviews is not abuse,
        // and rate-limiting reads would break normal scrolling.
        boolean isWrite = method.equals("POST") || method.equals("PUT")
                || method.equals("PATCH") || method.equals("DELETE");
        if (isWrite && (path.startsWith("/api/carts")
                || path.startsWith("/api/cart-items")
                || path.startsWith("/api/reviews")
                // Telemetry the app posts on its own, not something a person
                // chose to do. MUTATION because it fails OPEN: usage figures
                // going missing for an hour is a rounding error, while a
                // customer's app erroring because a limiter is down is not.
                || path.equals("/api/customers/me/app-session")
                // A return request is a customer action on their own order,
                // the same shape as adding to a cart. MUTATION rather than
                // CHECKOUT because it moves no money by itself - the shop's
                // approval does that, and approval is a staff route in the
                // ADMIN bucket.
                || path.startsWith("/api/returns")
                // Same category as app-session above: something the app posts
                // about itself, not something a person chose to do. It FAILS
                // OPEN, and that is the right way round here - the reporter
                // is an app that has already crashed, and a limiter outage
                // must not be the reason the shop never hears about it. The
                // row count has its own ceiling in CrashReportService, so
                // failing open costs noise, never disk.
                || path.equals("/api/client/crash-reports"))) {
            return Bucket.MUTATION;
        }

        if (path.startsWith("/api/payments/webhooks")) {
            return Bucket.WEBHOOK;
        }

        if (isWrite && path.startsWith("/api/uploads")) {
            return Bucket.UPLOAD;
        }

        if (isWrite && (path.equals("/api/notifications/broadcast")
                || path.startsWith("/api/admin/")
                || path.startsWith("/api/worker/")
                || path.startsWith("/api/deliveries")
                || path.startsWith("/api/inventory")
                || path.startsWith("/api/products")
                || path.startsWith("/api/categories")
                || path.startsWith("/api/product-variants")
                || (path.startsWith("/api/orders") && !path.equals("/api/orders/place")))) {
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
        if (bucket != Bucket.AUTH && bucket != Bucket.WEBHOOK) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
                if (user.getCustomerId() != null) {
                    return "cust:" + user.getCustomerId();
                }
                // A WORKER SESSION HAS NO customerId - their credentials live
                // on the roster row, not a customer account - so this used to
                // fall through to the IP. Three riders standing in the shop on
                // its wifi then shared one bucket, and one of them scanning
                // quickly could throttle the other two.
                if (user.getWorkerId() != null) {
                    return "worker:" + user.getWorkerId();
                }
            }
        }
        return "ip:" + clientIp(request);
    }

    private String clientIp(HttpServletRequest request) {
        return clientIpResolver.resolve(request);
    }
}
