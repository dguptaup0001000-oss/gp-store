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
    private final com.gpstore.worker.WorkerAccessService workerAccessService;

    public JwtFilter(JwtService jwtService,
                     CustomerAccountStatusService accountStatusService,
                     com.gpstore.worker.WorkerAccessService workerAccessService) {
        this.jwtService = jwtService;
        this.accountStatusService = accountStatusService;
        this.workerAccessService = workerAccessService;
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
                Long workerId = claims.get("workerId", Long.class);

                // A WORKER SESSION IS ITS OWN THING and never touches the
                // customers table. Their credentials live on the roster row,
                // so the whole status question - deleted, switched off,
                // suspended until four o'clock - is answered from there.
                //
                // Checked on EVERY request, not just at sign-in: these tokens
                // have no refresh token and last a shift, so a worker the shop
                // just removed has to stop working now, not this evening.
                if (workerId != null) {
                    com.gpstore.worker.WorkerAccess.Decision decision =
                            workerAccess(workerId);
                    if (!decision.allowed()) {
                        SecurityContextHolder.clearContext();
                        rejectWorker(response, decision.message());
                        return;
                    }
                    List<GrantedAuthority> workerAuthorities = new ArrayList<>();
                    for (String authority : RolePermissions.authorityNamesForRoleName(role)) {
                        workerAuthorities.add(new SimpleGrantedAuthority(authority));
                    }
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(
                                    new AuthenticatedUser(null, email, role, workerId),
                                    null,
                                    workerAuthorities));
                    filterChain.doFilter(request, response);
                    return;
                }

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

    private com.gpstore.worker.WorkerAccess.Decision workerAccess(Long workerId) {
        try {
            return workerAccessService.resolve(workerId);
        } catch (RuntimeException ex) {
            // Fail closed, exactly as the customer path does: if we cannot
            // confirm this worker is still on the roster, do not authenticate.
            log.warn("Worker status check failed for workerId={}: {}", workerId, ex.getMessage());
            return new com.gpstore.worker.WorkerAccess.Decision(
                    com.gpstore.worker.WorkerAccess.Verdict.DELETED,
                    "Could not confirm this worker account. Try again in a moment.");
        }
    }

    private static void rejectWorker(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        // The worker app shows this sentence verbatim, so it has to be the
        // one WorkerAccess wrote - "paused for another 3 hours" is the whole
        // difference between waiting and going home.
        response.getWriter().write(
                "{\"status\":401,\"error\":\"Unauthorized\",\"message\":"
                        + com.fasterxml.jackson.databind.node.TextNode.valueOf(message) + "}");
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
