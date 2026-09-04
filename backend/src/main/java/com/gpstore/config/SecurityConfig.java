package com.gpstore.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.http.HttpMethod;
import com.gpstore.security.AdminPermission;
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
                            "{\"status\":403,\"error\":\"Forbidden\",\"message\":\"" + accessDeniedMessage(request)
                                    + "\",\"path\":\"" + request.getRequestURI() + "\"}");
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
                // OPENING HOURS AND WHETHER ORDERS ARE BEING TAKEN. Public for
                // the same reason: a customer browsing at 3am before signing in
                // is exactly who needs to be told the shop is open and their
                // order arrives at 9am. It exposes what a sign on the door
                // would - no customer, order or staff data - and it is
                // advisory: the order path re-checks acceptance itself, so a
                // client that ignores this response gets a 409 at checkout
                // rather than an order the shop cannot deliver.
                .requestMatchers(HttpMethod.GET, "/api/store/status").permitAll()
                .requestMatchers("/api/health", "/api/health/**").permitAll()
                .requestMatchers("/api/version").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/actuator/**").hasAuthority(AdminPermission.SYSTEM_ADMIN.authority())
                // Springdoc auto-generates these from your existing @RestController
                // annotations - no extra code needed. Admin-only for now since
                // you're pre-launch; open these up once you want partners/devs
                // to browse the API docs themselves.
                .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").hasAuthority(AdminPermission.SYSTEM_ADMIN.authority())
                // The exact list-everything endpoint is admin-only - must come BEFORE
                // the broader "/api/reviews/**" public browsing rule below, since Spring
                // Security matches rules in declared order and "/**" would otherwise
                // also match this exact path first.
                .requestMatchers(HttpMethod.GET, "/api/reviews").hasAuthority(AdminPermission.REVIEWS_MODERATE.authority())
                // Same reasoning - moderation (deleting someone else's review)
                // must be admin-only, and there's no other rule that would
                // cover this DELETE path otherwise.
                .requestMatchers(HttpMethod.DELETE, "/api/reviews/*/moderate").hasAuthority(AdminPermission.REVIEWS_MODERATE.authority())
                // Same ordering reason as /api/reviews above - the admin
                // "everything including inactive" product list must come
                // before the broad public GET /api/products/** rule below.
                .requestMatchers(HttpMethod.GET, "/api/products/admin/**").hasAuthority(AdminPermission.CATALOG_VIEW.authority())
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
                .requestMatchers(HttpMethod.POST, "/api/products/**", "/api/categories/**", "/api/product-variants/**").hasAuthority(AdminPermission.CATALOG_MANAGE.authority())
                .requestMatchers(HttpMethod.PUT, "/api/products/**", "/api/categories/**", "/api/product-variants/**").hasAuthority(AdminPermission.CATALOG_MANAGE.authority())
                .requestMatchers(HttpMethod.DELETE, "/api/products/**", "/api/categories/**", "/api/product-variants/**").hasAuthority(AdminPermission.CATALOG_MANAGE.authority())
                .requestMatchers("/api/inventory/**").hasAuthority(AdminPermission.INVENTORY_MANAGE.authority())
                .requestMatchers("/api/uploads/**").hasAuthority(AdminPermission.CATALOG_MANAGE.authority())
                .requestMatchers("/api/orders").hasAuthority(AdminPermission.ORDERS_VIEW.authority())
                // CATALOG ADMINISTRATION. Every route under here can insert a
                // thousand products, make a thousand outbound requests, or
                // delete every test product in the shop - so it is the
                // narrowest possible grant, and it sits ABOVE the broader
                // rules so nothing below can widen it by accident.
                .requestMatchers("/api/admin/catalog/**").hasAuthority(AdminPermission.SYSTEM_ADMIN.authority())
                // TERRITORY ADMINISTRATION. Every route under here edits the
                // permanent delivery map - a boundary, a rider's territory,
                // which territories may lend to each other. Redrawing one
                // silently reroutes every future order in that area, so this
                // is admin-only for the same reason catalog administration
                // above is, and sits beside it so neither can be widened by a
                // broader rule further down.
                .requestMatchers("/api/admin/territory/**").hasAuthority(AdminPermission.DELIVERY_MANAGE.authority())
                // WORKER ACCOUNTABILITY. Issuing a QR token prints a label that
                // makes an order scannable, and reassigning one overrides the
                // permanent territory rules - both belong to whoever runs the
                // shop, not to the people on the floor.
                .requestMatchers("/api/admin/worker/**").hasAuthority(AdminPermission.DELIVERY_MANAGE.authority())
                // DELIVERY PRICING. Editing these numbers changes what every
                // future customer pays, and the per-order breakdown exposes
                // cost prices and margins - neither is customer-readable.
                .requestMatchers("/api/admin/delivery-pricing/**").hasAuthority(AdminPermission.DELIVERY_MANAGE.authority())
                // THE MORNING PACKING LIST. Read-only, and the people who pack
                // the boxes need it - so ORDERS_VIEW, not the switch below.
                // Placed BEFORE the broader /api/admin/store/** rule because
                // Spring applies the first matching rule, so the narrower path
                // has to come first or it is never reached.
                .requestMatchers(HttpMethod.GET, "/api/admin/store/preparation")
                    .hasAuthority(AdminPermission.ORDERS_VIEW.authority())
                // PAUSING ORDERS AND CLOSING DAYS. Turning this off stops the
                // shop earning and closing a day cancels deliveries customers
                // are expecting; both belong with whoever decides when the vans
                // run, which is DELIVERY_MANAGE - held by ADMIN, SUPER_ADMIN,
                // MANAGER and DELIVERY_MANAGER, and deliberately not by a
                // counter clerk or by support.
                .requestMatchers("/api/admin/store/**").hasAuthority(AdminPermission.DELIVERY_MANAGE.authority())
                // ApplicationId / Flutter UI is not authorization. A customer
                // JWT must 403 here whether it arrived from the shop APK,
                // a leftover combined APK, or a script.
                .requestMatchers("/api/admin/**").hasAuthority(AdminPermission.SYSTEM_ADMIN.authority())
                // THE WORKER APP. Every route here resolves the worker from the
                // JWT and never from the request, so ADMIN is included only so
                // an administrator can exercise the same flow while setting a
                // phone up - it grants no ability a worker does not already
                // have over their own record.
                // The worker app's own sign-in. Must sit BEFORE the rule
                // below, or the worker could never reach the endpoint that
                // gets them a token in the first place.
                .requestMatchers(HttpMethod.POST, "/api/worker/auth/login").permitAll()
                // The roster page in the admin dashboard. Same permission that
                // already means "runs dispatch"; deliberately NOT reachable by
                // a delivery worker, who must not be able to edit the roster
                // they appear on.
                .requestMatchers("/api/admin/workers/**").hasAuthority(AdminPermission.DELIVERY_MANAGE.authority())
                // MINTING A LABEL IS AN ADMIN ACT, and this rule has to sit
                // above the general worker rule below or the more permissive
                // one would swallow it. A delivery worker who could issue a
                // label could issue their own credential and claim any order
                // without touching the carton, which would make both the QR
                // token and the typed pack code decorative.
                .requestMatchers(HttpMethod.POST, "/api/worker/orders/*/label")
                    .hasAuthority(AdminPermission.DELIVERY_MANAGE.authority())
                .requestMatchers("/api/worker/**").hasAnyAuthority(AdminPermission.DELIVERY_MANAGE.authority(), "ROLE_DELIVERY_BOY")
                .requestMatchers("/api/orders/admin/**").hasAuthority(AdminPermission.ORDERS_VIEW.authority())
                .requestMatchers("/api/orders/customer/**").hasAuthority(AdminPermission.ORDERS_VIEW.authority())
                .requestMatchers(HttpMethod.PUT, "/api/orders/*/status").hasAuthority(AdminPermission.ORDERS_MANAGE.authority())
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
                .requestMatchers(HttpMethod.POST, "/api/payments/order/*/checkout-session").hasAnyAuthority(AdminPermission.PAYMENTS_VIEW.authority(), "ROLE_CUSTOMER")
                .requestMatchers(HttpMethod.POST, "/api/payments/order/*/verify").hasAnyAuthority(AdminPermission.PAYMENTS_VIEW.authority(), "ROLE_CUSTOMER")
                .requestMatchers(HttpMethod.POST, "/api/payments").hasAnyAuthority(AdminPermission.PAYMENTS_VIEW.authority(), "ROLE_CUSTOMER")
                .requestMatchers(HttpMethod.GET, "/api/payments/**").hasAuthority(AdminPermission.PAYMENTS_VIEW.authority())
                .requestMatchers("/api/payments/order/*/refund/**").hasAuthority(AdminPermission.PAYMENTS_REFUND.authority())
                .requestMatchers("/api/payments/order/*/cod/**").hasAnyAuthority(AdminPermission.PAYMENTS_MANAGE.authority(), "ROLE_DELIVERY_BOY")
                .requestMatchers("/api/payments/order/*/upi/**").hasAuthority(AdminPermission.PAYMENTS_MANAGE.authority())
                // Any logged-in customer can check their own order's delivery ETA -
                // must come before the broader /api/deliveries/** staff-only rule below.
                .requestMatchers(HttpMethod.GET, "/api/deliveries/my-order/**").authenticated()
                // NOTE: your real controller path is /api/deliveries/** (plural) - the
                // earlier /api/delivery/** pattern here never matched it, leaving delivery
                // status updates open to any authenticated customer. Fixed below.
                // A delivery partner setting their own availability - must
                // come before the broader delivery-partners admin-only rule
                // below, same ordering reason used throughout this file.
                .requestMatchers(HttpMethod.PUT, "/api/delivery-partners/me/availability").hasAnyAuthority(AdminPermission.DELIVERY_VIEW.authority(), "ROLE_DELIVERY_BOY")
                .requestMatchers(HttpMethod.GET, "/api/delivery-partners/me").hasAnyAuthority(AdminPermission.DELIVERY_VIEW.authority(), "ROLE_DELIVERY_BOY")
                .requestMatchers(HttpMethod.PUT, "/api/delivery-partners/me/location").hasAnyAuthority(AdminPermission.DELIVERY_VIEW.authority(), "ROLE_DELIVERY_BOY")
                // Roster management (create, view everyone, bulk-edit ANY
                // partner's record) is admin-only - a delivery partner has
                // no legitimate need to see or edit the whole roster, only
                // their own assignments (covered by /api/deliveries/** below)
                // and their own availability (covered just above).
                .requestMatchers("/api/delivery-partners/**").hasAuthority(AdminPermission.DELIVERY_MANAGE.authority())
                // Batch management is a dispatch/admin concern - a delivery
                // partner interacts with their OWN deliveries, never batches directly.
                .requestMatchers("/api/delivery-batches/**").hasAuthority(AdminPermission.DELIVERY_MANAGE.authority())
                // Fleet-wide delivery lists, breach review, and assignment are
                // dispatch/admin work. A partner may only see and update their
                // own rows (status, my-assignments, and a scoped GET by id).
                .requestMatchers(HttpMethod.GET, "/api/deliveries/breached").hasAuthority(AdminPermission.DELIVERY_VIEW.authority())
                .requestMatchers(HttpMethod.GET, "/api/deliveries").hasAuthority(AdminPermission.DELIVERY_VIEW.authority())
                .requestMatchers(HttpMethod.POST, "/api/deliveries/assign").hasAuthority(AdminPermission.DELIVERY_MANAGE.authority())
                .requestMatchers(HttpMethod.POST, "/api/deliveries/auto-assign").hasAuthority(AdminPermission.DELIVERY_MANAGE.authority())
                .requestMatchers(HttpMethod.POST, "/api/deliveries/assign-vehicle").hasAuthority(AdminPermission.DELIVERY_MANAGE.authority())
                .requestMatchers("/api/deliveries/**").hasAnyAuthority(AdminPermission.DELIVERY_VIEW.authority(), "ROLE_DELIVERY_BOY")
                .requestMatchers("/api/coupons/**").hasAuthority(AdminPermission.COUPONS_MANAGE.authority())
                .requestMatchers(HttpMethod.GET, "/api/notifications/mine").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/notifications/unread-count").authenticated()
                // Same ordering reason as /mine above - these must come
                // before the broader /api/notifications/** admin-only rule.
                .requestMatchers(HttpMethod.PUT, "/api/notifications/*/read").authenticated()
                .requestMatchers(HttpMethod.PUT, "/api/notifications/read-all").authenticated()
                .requestMatchers(HttpMethod.DELETE, "/api/notifications/*").authenticated()
                .requestMatchers("/api/notifications/**").hasAuthority(AdminPermission.BROADCAST_SEND.authority())
                // These were missing entirely and fell through to plain "authenticated",
                // which let any logged-in customer read/cancel ANY invoice, or read/write
                // ANY customer's cart items / order items / wishlist directly.
                // A customer's own invoice - must come before the broader
                // /api/invoices/** admin-only rule below, same ordering
                // reason used throughout this file.
                .requestMatchers(HttpMethod.GET, "/api/invoices/my-order/**").authenticated()
                .requestMatchers("/api/invoices/**").hasAuthority(AdminPermission.ORDERS_VIEW.authority())
                .requestMatchers("/api/audit-logs/**").hasAuthority(AdminPermission.AUDIT_VIEW.authority())
                .requestMatchers("/api/analytics/**").hasAuthority(AdminPermission.ANALYTICS_VIEW.authority())
                // CARTS: READING IS NOT WRITING - the same fix as order lines
                // below, one resource over, and missed when that one was made.
                //
                // This was one rule for the whole path on CUSTOMERS_VIEW, a
                // READ permission held by SUPPORT, DELIVERY_MANAGER and
                // ORDER_MANAGER. Under it sat POST /api/cart-items (a CartItem
                // bound straight from the body), DELETE /api/cart-items/{id},
                // and DELETE /api/cart-items/cart/{id} - which empties a whole
                // basket. Proved with real tokens before this line was written:
                // a SUPPORT account got 200 OK and wrote cart row 8421, and
                // both SUPPORT and DELIVERY_MANAGER cleared a cart.
                //
                // RolePermissions describes SUPPORT as "Changes nothing else"
                // and DELIVERY_MANAGER as running dispatch. Neither should be
                // able to touch a shopper's basket while they are shopping.
                //
                // Reads stay on CUSTOMERS_VIEW: answering "what is in their
                // cart" is exactly why a support agent can reach this at all.
                .requestMatchers(HttpMethod.POST, "/api/cart-items", "/api/cart-items/**")
                    .hasAuthority(AdminPermission.CUSTOMERS_MANAGE.authority())
                .requestMatchers(HttpMethod.PUT, "/api/cart-items/**")
                    .hasAuthority(AdminPermission.CUSTOMERS_MANAGE.authority())
                .requestMatchers(HttpMethod.PATCH, "/api/cart-items/**")
                    .hasAuthority(AdminPermission.CUSTOMERS_MANAGE.authority())
                .requestMatchers(HttpMethod.DELETE, "/api/cart-items/**")
                    .hasAuthority(AdminPermission.CUSTOMERS_MANAGE.authority())
                .requestMatchers("/api/cart-items/**").hasAuthority(AdminPermission.CUSTOMERS_VIEW.authority())
                // ORDER LINES: READING IS NOT WRITING.
                //
                // This was one rule for the whole path on ORDERS_VIEW - a
                // READ permission - and POST /api/order-items adds a priced
                // line to an order. SUPPORT and DELIVERY_MANAGER hold
                // ORDERS_VIEW and not ORDERS_MANAGE, so either could attach a
                // line to any order in the shop. Proved with real tokens
                // against a real port before this line existed: both got 200
                // and a persisted row.
                //
                // The write now needs the manage permission, and the service
                // prices the line itself rather than believing the body.
                .requestMatchers(HttpMethod.POST, "/api/order-items", "/api/order-items/**")
                    .hasAuthority(AdminPermission.ORDERS_MANAGE.authority())
                .requestMatchers(HttpMethod.PUT, "/api/order-items/**")
                    .hasAuthority(AdminPermission.ORDERS_MANAGE.authority())
                .requestMatchers(HttpMethod.DELETE, "/api/order-items/**")
                    .hasAuthority(AdminPermission.ORDERS_MANAGE.authority())
                .requestMatchers("/api/order-items/**").hasAuthority(AdminPermission.ORDERS_VIEW.authority())
                .requestMatchers(HttpMethod.GET, "/api/wishlists").hasAuthority(AdminPermission.CUSTOMERS_VIEW.authority())
                .requestMatchers(HttpMethod.POST, "/api/customers").hasAuthority(AdminPermission.CUSTOMERS_MANAGE.authority())
                .requestMatchers("/api/customers/email/**", "/api/customers/mobile/**").hasAuthority(AdminPermission.CUSTOMERS_VIEW.authority())
                .requestMatchers(HttpMethod.GET, "/api/customers").hasAuthority(AdminPermission.CUSTOMERS_VIEW.authority())
                // THE WHOLE FILE ON ONE PERSON: name, phone, every saved
                // address, what is in their basket, what they have spent.
                // Without this line it falls through to anyRequest()
                // .authenticated(), which means ANY signed-in customer could
                // read ANY other customer's home address by changing a number
                // in a URL. Staff only, and the same permission that already
                // means "may look at customers".
                .requestMatchers(HttpMethod.GET, "/api/customers/*/detail")
                    .hasAuthority(AdminPermission.CUSTOMERS_VIEW.authority())
                // RETURNS: the staff side of the counter.
                //
                // The customer routes below these need no rule - they take the
                // account from the token and never from the URL, so there is
                // nothing to tamper with. The STAFF routes do, and urgently:
                // /api/returns/** otherwise falls through to
                // anyRequest().authenticated(), which would let any signed-in
                // shopper approve their own return and send themselves a
                // refund. Approving is the one that moves money, so it takes
                // the refund permission rather than a general admin one.
                .requestMatchers(HttpMethod.POST, "/api/returns/*/approve")
                    .hasAuthority(AdminPermission.PAYMENTS_REFUND.authority())
                .requestMatchers(HttpMethod.POST, "/api/returns/*/reject")
                    .hasAuthority(AdminPermission.ORDERS_MANAGE.authority())
                .requestMatchers(HttpMethod.GET, "/api/returns/pending", "/api/returns/pending/count")
                    .hasAuthority(AdminPermission.ORDERS_VIEW.authority())

                // Without this, any authenticated customer could deactivate
                // (or reactivate) ANY other customer's account.
                .requestMatchers(HttpMethod.PUT, "/api/customers/*/active").hasAuthority(AdminPermission.CUSTOMERS_MANAGE.authority())

                // ANY SIGNED-IN APP MAY REPORT ITS OWN CRASH - a rider's
                // session and a customer's session both land here, which is
                // the point: the worker APK has no other way to say it died.
                //
                // Stated explicitly even though anyRequest() below would
                // permit exactly the same thing. A rule that is only correct
                // because of what it falls through to is one reordering away
                // from being wrong, and this endpoint writes rows. WRITE
                // ONLY: there is no GET here to open up, so nothing about
                // this line can expose one app's crashes to another.
                .requestMatchers(HttpMethod.POST, "/api/client/crash-reports").authenticated()

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

    /**
     * What to tell someone whose request was refused here.
     *
     * A denial on a worker route is worth a real sentence. The worker app has
     * one screen - sign in - and reaching this handler with a valid token
     * means the password was right and the account simply is not a delivery
     * worker. The generic line sent that person back to retype a password
     * that was never wrong; the shopkeeper watching over their shoulder had
     * nothing to act on either. This names the exact control that fixes it.
     *
     * DeliveryWorkerController has its own, better sentence for the next step
     * along - linked account, no roster row - but it can only be reached
     * AFTER this filter lets the request through, so it never ran for the
     * case people actually hit.
     *
     * Everything else keeps the generic line on purpose: for the admin
     * console and the customer app, a refusal that described the missing
     * permission would be telling whoever probed it what to go looking for.
     */
    static String accessDeniedMessage(jakarta.servlet.http.HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return GENERIC_ACCESS_DENIED;
        }
        // The app runs under a context path (/v1), and getRequestURI keeps it.
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        if (path.startsWith("/api/worker/")) {
            return WORKER_ACCESS_DENIED;
        }
        return GENERIC_ACCESS_DENIED;
    }

    static final String GENERIC_ACCESS_DENIED = "You don't have permission to do that.";

    /**
     * No double quotes and no backslashes: this is written straight into a
     * hand-built JSON body, which does no escaping. (An apostrophe is fine -
     * the generic message above carries one.)
     */
    static final String WORKER_ACCESS_DENIED =
            "This account is not set up as a delivery worker. Ask the shop to open your "
                    + "delivery partner record in the admin app and put this email under "
                    + "Worker app sign-in.";

}
