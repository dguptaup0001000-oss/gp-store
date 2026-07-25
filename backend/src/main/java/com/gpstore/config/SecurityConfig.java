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
        configuration.setAllowedOrigins(List.of(allowedOrigins.split(",")));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
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
            .authorizeHttpRequests(auth -> auth
                // Public: auth endpoints and read-only catalog browsing
                .requestMatchers(HttpMethod.POST, "/api/auth/logout-all").authenticated()
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/health/**").permitAll()
                .requestMatchers("/actuator/health").permitAll()
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

                // Admin-only: catalog and inventory management, cross-customer views,
                // payment/order status mutation, delivery operations
                .requestMatchers(HttpMethod.POST, "/api/products/**", "/api/categories/**", "/api/product-variants/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/products/**", "/api/categories/**", "/api/product-variants/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/products/**", "/api/categories/**", "/api/product-variants/**").hasRole("ADMIN")
                .requestMatchers("/api/inventory/**").hasRole("ADMIN")
                .requestMatchers("/api/orders").hasRole("ADMIN")
                .requestMatchers("/api/orders/customer/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/orders/*/status").hasRole("ADMIN")
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
                .requestMatchers("/api/deliveries/**", "/api/delivery-partners/**", "/api/delivery-batches/**").hasAnyRole("ADMIN", "DELIVERY_BOY")
                .requestMatchers("/api/coupons/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/notifications/mine").authenticated()
                .requestMatchers("/api/notifications/**").hasRole("ADMIN")
                // These were missing entirely and fell through to plain "authenticated",
                // which let any logged-in customer read/cancel ANY invoice, or read/write
                // ANY customer's cart items / order items / wishlist directly.
                .requestMatchers("/api/invoices/**").hasRole("ADMIN")
                .requestMatchers("/api/audit-logs/**").hasRole("ADMIN")
                .requestMatchers("/api/analytics/**").hasRole("ADMIN")
                .requestMatchers("/api/cart-items/**").hasRole("ADMIN")
                .requestMatchers("/api/order-items/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/wishlists").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/customers").hasRole("ADMIN")
                .requestMatchers("/api/customers/email/**", "/api/customers/mobile/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/customers").hasRole("ADMIN")

                // Everything else requires a valid, authenticated customer
                .anyRequest().authenticated()
            )
            .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtFilter,
                    UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
