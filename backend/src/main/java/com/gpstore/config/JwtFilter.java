package com.gpstore.config;

import com.gpstore.security.AuthenticatedUser;
import com.gpstore.security.CustomerAccountStatusService;
import com.gpstore.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import com.gpstore.security.AdminPermission;
import com.gpstore.security.RolePermissions;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
public class JwtFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtFilter.class);

    private final JwtService jwtService;
    private final CustomerAccountStatusService accountStatusService;

    public JwtFilter(JwtService jwtService, CustomerAccountStatusService accountStatusService) {
        this.jwtService = jwtService;
        this.accountStatusService = accountStatusService;
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

                // A valid signature is not enough. Deactivating an account
                // revokes refresh tokens but an already-issued access JWT
                // would otherwise keep working until expiry. Re-check live
                // status AND role here for every role (customer, worker,
                // admin) - they are all Customer rows. A demoted rider must
                // not keep worker authorities minted at login.
                //
                // Refresh/logout still run: those endpoints authenticate
                // with the refresh token in the body. Blocking them would
                // leave a banned user unable to drop their session. Other
                // /api/auth/** calls (change-password) must still reject
                // an inactive account.
                String path = request.getServletPath();
                if (!isSessionLifecycleAuthPath(path)) {
                    CustomerAccountStatusService.Snapshot snapshot = accountIsLive(customerId);
                    if (!snapshot.usable()
                            || !CustomerAccountStatusService.roleMatches(role, snapshot.role())) {
                        SecurityContextHolder.clearContext();
                        rejectInactive(response);
                        return;
                    }
                }

                AuthenticatedUser principal =
                        new AuthenticatedUser(customerId, email, role);

                // ROLE_<name> as before, plus one PERM_<name> per permission
                // the role carries.
                //
                // DERIVED FROM THE ROLE, NEVER CARRIED IN THE TOKEN. If the
                // JWT listed its own permissions, a promotion would not take
                // effect until the token expired and - far worse - a
                // demotion would not either. The role recheck a few lines
                // above already proves this role still matches the live
                // database row, so recomputing the set here means a change
                // of role applies on the very next request.
                List<GrantedAuthority> authorities = new ArrayList<>();
                for (String authority : RolePermissions.authorityNamesForRoleName(role)) {
                    authorities.add(new SimpleGrantedAuthority(authority));
                }

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

    static boolean isSessionLifecycleAuthPath(String path) {
        return "/api/auth/refresh".equals(path)
                || "/api/auth/logout".equals(path)
                || "/api/auth/logout-all".equals(path);
    }

    private CustomerAccountStatusService.Snapshot accountIsLive(Long customerId) {
        try {
            return accountStatusService.resolve(customerId);
        } catch (RuntimeException ex) {
            // Fail closed: if we cannot confirm the account is live, do not
            // authenticate. A database blip already breaks checkout; it must
            // not be a window in which a banned JWT is accepted.
            log.warn("Account status check failed for customerId={}: {}", customerId, ex.getMessage());
            return CustomerAccountStatusService.Snapshot.unusable();
        }
    }

    private static void rejectInactive(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"This account is no longer active\"}");
    }
}
