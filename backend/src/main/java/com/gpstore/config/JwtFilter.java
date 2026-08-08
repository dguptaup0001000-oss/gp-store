package com.gpstore.config;

import com.gpstore.security.AuthenticatedUser;
import com.gpstore.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {

            String token = header.substring(7);

            // Parses and signature-verifies the token exactly once (see
            // JwtService.parseClaimsIfValid's doc comment) - previously this
            // called isTokenValid() + extractEmail() + extractCustomerId() +
            // extractRole(), each independently re-verifying the same
            // signature, 4x the real cryptographic work per request for
            // nothing.
            io.jsonwebtoken.Claims claims = jwtService.parseClaimsIfValid(token);

            if (claims != null) {

                String email = claims.getSubject();
                Long customerId = claims.get("customerId", Long.class);
                String role = claims.get("role", String.class);

                AuthenticatedUser principal =
                        new AuthenticatedUser(customerId, email, role);

                List<GrantedAuthority> authorities =
                        role != null
                                ? List.of(new SimpleGrantedAuthority("ROLE_" + role))
                                : List.of();

                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(
                                principal,
                                null,
                                authorities);

                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }

        filterChain.doFilter(request, response);
    }
}
