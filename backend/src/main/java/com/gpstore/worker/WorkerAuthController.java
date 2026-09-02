package com.gpstore.worker;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The worker app's front door.
 *
 * SEPARATE FROM /api/auth ON PURPOSE. Customer and staff sessions are Customer
 * rows with roles and refresh tokens; a worker session is a roster row and a
 * shift-length token. Sharing one endpoint would mean one of the two lying
 * about what it returns.
 */
@RestController
@RequestMapping("/api/worker/auth")
public class WorkerAuthController {

    private final WorkerAuthService authService;

    public WorkerAuthController(WorkerAuthService authService) {
        this.authService = authService;
    }

    /**
     * One field for the identifier, because the rider types whichever of the
     * two they remember and the server works out which it is.
     */
    public static class WorkerLoginRequest {

        @NotBlank(message = "Enter your phone number or email address.")
        private String identifier;

        /**
         * WRITE_ONLY plus an overridden toString: a validation error that
         * echoes the request body, or one careless log line, must not be able
         * to carry a password out of here.
         */
        @NotBlank(message = "Enter your password.")
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        private String password;

        public String getIdentifier() {
            return identifier;
        }

        public void setIdentifier(String identifier) {
            this.identifier = identifier;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        @Override
        public String toString() {
            return "WorkerLoginRequest{identifier=" + identifier + ", password=***}";
        }
    }

    @PostMapping("/login")
    public Map<String, Object> login(@jakarta.validation.Valid @RequestBody WorkerLoginRequest request) {
        WorkerAuthService.Session session =
                authService.login(request.getIdentifier(), request.getPassword());

        // No refreshToken field at all, rather than one set to null: this
        // session genuinely has none, and the client already treats a missing
        // refresh token as "sign in again", which is the correct behaviour
        // once the token finally expires.
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("accessToken", session.accessToken());
        body.put("workerId", session.workerId());
        body.put("name", session.name());
        body.put("email", session.loginEmail());
        body.put("mobile", session.mobile());
        return body;
    }
}
