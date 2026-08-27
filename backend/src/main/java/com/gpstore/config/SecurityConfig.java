package com.gpstore.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtFilter jwtFilter;
    private final RateLimitFilter rateLimitFilter;

    // Comma-separated allowed origins. No default of "*" - that would be
    // wide open. Set this explicitly per environment (your Flutter web build's
    // real domain, your admin dashboard's domain, etc.) once you have one.
    @Value("${cors.allowed-origins:http://localhost:3000}")
    private String allowedOrigins;

    public SecurityConfig(JwtFilter jwtFilter, RateLimitFilter rateLimitFilter) {
        this.jwtFilter = jwtFilter;
        this.rateLimitFilter = rateLimitFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Without this, any browser-based client (Flutter web, an admin
     * dashboard, anything that isn't a native mobile app making raw HTTP
     * calls) would be silently blocked by the browser's CORS policy - not a
     * bug that shows up in Postman, only in an actual browser. Native mobile
     * apps (your Flutter customer/delivery apps) aren't affected by CORS at
     * all, so this specifically matters once there's a web-based client.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // TRIMMED, and blanks dropped. CORS_ALLOWED_ORIGINS is typed by a
        // human into a deployment console, and "https://a.com, https://b.com"
        // is the natural way to write a list. Without trimming, the second
        // origin is stored as " https://b.com" - with a leading space - which
        // never matches any real Origin header. The failure is invisible
        // server-side and shows up only as a browser CORS error on one of the
        // two origins, which is a miserable thing to debug.
        //
        // Deliberately NOT a fallback to "*": an unparseable or empty list
        // must end up allowing nothing, not everything. allowCredentials is
        // true below, and a wildcard with credentials is exactly the
        // combination that turns any site into a reader of authenticated
        // responses.
        configuration.setAllowedOrigins(
                java.util.Arrays.stream(allowedOrigins.split(","))
                        .map(String::trim)
                        .filter(origin -> !origin.isEmpty())
                        .toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        // Idempotency-Key is what checkout sends on POST /api/orders/place.
        // Without it here, a browser client (Flutter web) is blocked on the
        // CORS preflight even though native apps are unaffected. Do not
        // widen this to "*" — extra request headers would be allowed too.
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .headers(headers -> headers
                    // Spring Security's default Cache-Control is no-store on
                    // every response. That is correct for auth, checkout and
                    // orders. It is wrong for the public catalogue: phones
                    // and Traefik were forbidden from reusing a 15-second
                    // feed page that the application already caches in
                    // Caffeine. CatalogPublicCacheFilter writes the public
                    // policy; everything else gets no-store there too.
                    .cacheControl(cache -> cache.disable())
                    // Forces HTTPS on every subsequent request for a year once a
                    // browser sees this - only actually matters once you're
                    // serving over HTTPS (which any real deployment should be).
                    .httpStrictTransportSecurity(hsts -> hsts
                            .includeSubDomains(true)
                            .maxAgeInSeconds(31536000))
                    // Stops the browser from guessing content types and
                    // executing something as a different type than declared.
                    .contentTypeOptions(contentTypeOptions -> {})
                    // Blocks this API's responses from being embedded in an
                    // iframe elsewhere - defends against clickjacking.
                    .frameOptions(frameOptions -> frameOptions.deny())
                    .referrerPolicy(referrer -> referrer
                            .policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                    // Content-Security-Policy deliberately NOT added here: a
                    // CSP has to list the exact script/style/image sources your
                    // actual frontend uses, and guessing wrong risks silently
                    // breaking Swagger UI's own JS (served by this same app) or
                    // your future admin dashboard. Add this once you know what
                    // it needs to allow, not before.
            )
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Without this, Spring Security's default for "no/expired/invalid
            // token on a protected endpoint" is Http403ForbiddenEntryPoint -
            // a 403, not a 401. JwtFilter never throws an AuthenticationException
            // itself (it just leaves the SecurityContext empty on a bad token),
            // so that default is what actually answers every such request. The
            // frontend's ApiClient only auto-refreshes-and-retries on a 401
            // (see _handleError in api_client.dart) - it correctly leaves 403
            // alone since 403 is supposed to mean "authenticated but not
            // allowed", which should never trigger a token refresh. With the
            // real failure mode reported as 403, that refresh path never ran,
            // so an expired access token on app open (a valid refresh token
            // still exists at that point every time) surfaced as a dead-end
            // "couldn't load your account" error instead of transparently
            // refreshing. Returning a real 401 here restores that path.
            //
            // THE ACCESS-DENIED HANDLER BELOW IS NOT OPTIONAL, and its absence
            // silently undid the paragraph above. Without it, an
            // AccessDeniedException - a LOGGED-IN customer touching an
            // admin-only route - is not written by Spring Security at all. It
            // propagates, the container forwards to /error, that forward
            // re-enters this filter chain, /error matches no permitAll rule,
            // and the entry point above answers it: 401, with
            // "path":"/error" rather than the path the caller asked for.
            //
            // So every 403 in this application reached the app as a 401, and
            // ApiClient._handleError treats 401 as "access token expired":
            // refresh (which succeeds, the token was never the problem),
            // retry, get 401 again, refresh again. The 401 branch carries no
            // attempt counter, unlike _retryIfSafe, so that is unbounded - and
            // each pass rotates the refresh token.
            //
            // MockMvc cannot see this. It does not run the container's ERROR
            // dispatch, so the authorization tests observe the 403 Spring
            // Security raises and pass, while the real server returns 401.
            // AccessDeniedStatusTest drives a real port for exactly that reason.
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write(
                            "{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"Authentication required\",\"path\":\""
                                    + request.getRequestURI() + "\"}");
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json");
                    response.getWriter().write(
                            "{\"status\":403,\"error\":\"Forbidden\",\"message\":\"You don't have permission to do that.\",\"path\":\""
                                    + request.getRequestURI() + "\"}");
                }))
            .authorizeHttpRequests(auth -> auth
                // Public: auth endpoints and read-only catalog browsing
                .requestMatchers(HttpMethod.POST, "/api/auth/logout-all").authenticated()
                // Same reasoning as logout-all above - without this explicit
                // override, change-password would fall under the blanket
                // permitAll below and be reachable with NO authentication.
                .requestMatchers(HttpMethod.PUT, "/api/auth/change-password").authenticated()
                .requestMatchers("/api/auth/**").permitAll()
                // Public - a customer needs this even before logging in
                // (e.g. locked out and needing to contact support).
                .requestMatchers("/api/store-info").permitAll()
                .requestMatchers("/api/health", "/api/health/**").permitAll()
                .requestMatchers("/api/version").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/actuator/**").hasRole("ADMIN")
                // Springdoc auto-generates these from your existing @RestController
                // annotations - no extra code needed. Admin-only for now since
                // you're pre-launch; open these up once you want partners/devs
                // to browse the API docs themselves.
                .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").hasRole("ADMIN")
                // The exact list-everything endpoint is admin-only - must come BEFORE
                // the broader "/api/reviews/**" public browsing rule below, since Spring
                // Security matches rules in declared order and "/**" would otherwise
                // also match this exact path first.
                .requestMatchers(HttpMethod.GET, "/api/reviews").hasRole("ADMIN")
                // Same reasoning - moderation (deleting someone else's review)
                // must be admin-only, and there's no other rule that would
                // cover this DELETE path otherwise.
                .requestMatchers(HttpMethod.DELETE, "/api/reviews/*/moderate").hasRole("ADMIN")
                // Same ordering reason as /api/reviews above - the admin
                // "everything including inactive" product list must come
                // before the broad public GET /api/products/** rule below.
                .requestMatchers(HttpMethod.GET, "/api/products/admin/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET,
                        "/api/products/**",
                        "/api/categories/**",
                        "/api/product-variants/**",
                        "/api/reviews/**",
                        "/api/recommendations/frequently-bought-together/**",
                        "/api/recommendations/trending"
                ).permitAll()

                // Any logged-in customer can preview a coupon's discount before checkout -
                // must come before the broader /api/coupons/** admin-only rule below.
                .requestMatchers(HttpMethod.GET, "/api/coupons/validate").authenticated()
                // Public "what offers exist right now" list - same ordering
                // reason as /validate above.
                .requestMatchers(HttpMethod.GET, "/api/coupons/active").permitAll()

                // Admin-only: catalog and inventory management, cross-customer views,
                // payment/order status mutation, delivery operations
                .requestMatchers(HttpMethod.POST, "/api/products/**", "/api/categories/**", "/api/product-variants/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/products/**", "/api/categories/**", "/api/product-variants/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/products/**", "/api/categories/**", "/api/product-variants/**").hasRole("ADMIN")
                .requestMatchers("/api/inventory/**").hasRole("ADMIN")
                .requestMatchers("/api/uploads/**").hasRole("ADMIN")
                .requestMatchers("/api/orders").hasRole("ADMIN")
                // CATALOG ADMINISTRATION. Every route under here can insert a
                // thousand products, make a thousand outbound requests, or
                // delete every test product in the shop - so it is the
                // narrowest possible grant, and it sits ABOVE the broader
                // rules so nothing below can widen it by accident.
                .requestMatchers("/api/admin/catalog/**").hasRole("ADMIN")
                // TERRITORY ADMINISTRATION. Every route under here edits the
                // permanent delivery map - a boundary, a rider's territory,
                // which territories may lend to each other. Redrawing one
                // silently reroutes every future order in that area, so this
                // is admin-only for the same reason catalog administration
                // above is, and sits beside it so neither can be widened by a
                // broader rule further down.
                .requestMatchers("/api/admin/territory/**").hasRole("ADMIN")
                // WORKER ACCOUNTABILITY. Issuing a QR token prints a label that
                // makes an order scannable, and reassigning one overrides the
                // permanent territory rules - both belong to whoever runs the
                // shop, not to the people on the floor.
                .requestMatchers("/api/admin/worker/**").hasRole("ADMIN")
                // DELIVERY PRICING. Editing these numbers changes what every
                // future customer pays, and the per-order breakdown exposes
                // cost prices and margins - neither is customer-readable.
                .requestMatchers("/api/admin/delivery-pricing/**").hasRole("ADMIN")
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                // THE WORKER APP. Every route here resolves the worker from the
                // JWT and never from the request, so ADMIN is included only so
                // an administrator can exercise the same flow while setting a
                // phone up - it grants no ability a worker does not already
                // have over their own record.
                .requestMatchers("/api/worker/**").hasAnyRole("ADMIN", "DELIVERY_BOY")
                .requestMatchers("/api/orders/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/orders/customer/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/orders/*/status").hasRole("ADMIN")
                // PUBLIC BY NECESSITY. Cashfree cannot present a JWT, so
                // this path cannot require one - the HMAC signature check in
                // PaymentWebhookController is what authenticates it instead.
                // Declared BEFORE the broad /api/payments/** admin rule below,
                // or that rule would shadow it and every webhook would 403.
                .requestMatchers(HttpMethod.POST, "/api/payments/webhooks/**").permitAll()
                // A customer starting or verifying their OWN order's payment.
                // Ownership is enforced inside GatewayPaymentService, which
                // returns not-found for someone else's order rather than
                // forbidden - so order ids cannot be probed.
                .requestMatchers(HttpMethod.POST, "/api/payments/order/*/checkout-session").hasAnyRole("ADMIN", "CUSTOMER")
                .requestMatchers(HttpMethod.POST, "/api/payments/order/*/verify").hasAnyRole("ADMIN", "CUSTOMER")
                .requestMatchers(HttpMethod.POST, "/api/payments").hasAnyRole("ADMIN", "CUSTOMER")
                .requestMatchers(HttpMethod.GET, "/api/payments/**").hasRole("ADMIN")
                .requestMatchers("/api/payments/order/*/refund/**").hasRole("ADMIN")
                .requestMatchers("/api/payments/order/*/cod/**").hasAnyRole("ADMIN", "DELIVERY_BOY")
                .requestMatchers("/api/payments/order/*/upi/**").hasRole("ADMIN")
                // Any logged-in customer can check their own order's delivery ETA -
                // must come before the broader /api/deliveries/** staff-only rule below.
                .requestMatchers(HttpMethod.GET, "/api/deliveries/my-order/**").authenticated()
                // NOTE: your real controller path is /api/deliveries/** (plural) - the
                // earlier /api/delivery/** pattern here never matched it, leaving delivery
                // status updates open to any authenticated customer. Fixed below.
                // A delivery partner setting their own availability - must
                // come before the broader delivery-partners admin-only rule
                // below, same ordering reason used throughout this file.
                .requestMatchers(HttpMethod.PUT, "/api/delivery-partners/me/availability").hasAnyRole("ADMIN", "DELIVERY_BOY")
                .requestMatchers(HttpMethod.GET, "/api/delivery-partners/me").hasAnyRole("ADMIN", "DELIVERY_BOY")
                .requestMatchers(HttpMethod.PUT, "/api/delivery-partners/me/location").hasAnyRole("ADMIN", "DELIVERY_BOY")
                // Roster management (create, view everyone, bulk-edit ANY
                // partner's record) is admin-only - a delivery partner has
                // no legitimate need to see or edit the whole roster, only
                // their own assignments (covered by /api/deliveries/** below)
                // and their own availability (covered just above).
                .requestMatchers("/api/delivery-partners/**").hasRole("ADMIN")
                // Batch management is a dispatch/admin concern - a delivery
                // partner interacts with their OWN deliveries, never batches directly.
                .requestMatchers("/api/delivery-batches/**").hasRole("ADMIN")
                // Fleet-wide delivery lists, breach review, and assignment are
                // dispatch/admin work. A partner may only see and update their
                // own rows (status, my-assignments, and a scoped GET by id).
                .requestMatchers(HttpMethod.GET, "/api/deliveries/breached").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/deliveries").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/deliveries/assign").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/deliveries/auto-assign").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/deliveries/assign-vehicle").hasRole("ADMIN")
                .requestMatchers("/api/deliveries/**").hasAnyRole("ADMIN", "DELIVERY_BOY")
                .requestMatchers("/api/coupons/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/notifications/mine").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/notifications/unread-count").authenticated()
                // Same ordering reason as /mine above - these must come
                // before the broader /api/notifications/** admin-only rule.
                .requestMatchers(HttpMethod.PUT, "/api/notifications/*/read").authenticated()
                .requestMatchers(HttpMethod.PUT, "/api/notifications/read-all").authenticated()
                .requestMatchers(HttpMethod.DELETE, "/api/notifications/*").authenticated()
                .requestMatchers("/api/notifications/**").hasRole("ADMIN")
                // These were missing entirely and fell through to plain "authenticated",
                // which let any logged-in customer read/cancel ANY invoice, or read/write
                // ANY customer's cart items / order items / wishlist directly.
                // A customer's own invoice - must come before the broader
                // /api/invoices/** admin-only rule below, same ordering
                // reason used throughout this file.
                .requestMatchers(HttpMethod.GET, "/api/invoices/my-order/**").authenticated()
                .requestMatchers("/api/invoices/**").hasRole("ADMIN")
                .requestMatchers("/api/audit-logs/**").hasRole("ADMIN")
                .requestMatchers("/api/analytics/**").hasRole("ADMIN")
                .requestMatchers("/api/cart-items/**").hasRole("ADMIN")
                .requestMatchers("/api/order-items/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/wishlists").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/customers").hasRole("ADMIN")
                .requestMatchers("/api/customers/email/**", "/api/customers/mobile/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/customers").hasRole("ADMIN")
                // Without this, any authenticated customer could deactivate
                // (or reactivate) ANY other customer's account.
                .requestMatchers(HttpMethod.PUT, "/api/customers/*/active").hasRole("ADMIN")

                // Everything else requires a valid, authenticated customer
                .anyRequest().authenticated()
            )
            // Order matters, and it changed: JwtFilter must run FIRST so the
            // SecurityContext is populated by the time RateLimitFilter looks
            // at it. Both used to be added with addFilterBefore against the
            // same target, which put the rate limiter first - so it never had
            // an authenticated principal to key on and fell back to IP for
            // every request, including authenticated ones. That is what made
            // carrier-NAT customers share a single quota. See
            // RateLimitFilter's identity strategy.
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(rateLimitFilter, JwtFilter.class);

        return http.build();
    }
}
