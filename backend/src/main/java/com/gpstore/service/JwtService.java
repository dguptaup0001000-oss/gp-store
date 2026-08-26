package com.gpstore.service;

import com.gpstore.config.PlaceholderValues;
import com.gpstore.entity.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.Key;
import java.util.Date;

@Service
public class JwtService {

    /**
     * The exact development fallback in application.properties. Matched by
     * value rather than by "is a profile active" so it cannot be defeated by
     * a misconfigured profile: what actually matters is whether the running
     * app is signing real tokens with a secret that is published in this
     * repository, and that is answerable directly.
     */
    static final String DEV_FALLBACK_SECRET =
            "dev-only-change-me-GPSTORESECRETKEY123456789012345678901234567890";

    /**
     * HS256 needs at least 256 bits of key material; jjwt rejects anything
     * shorter at signing time. Checked here so a too-short JWT_SECRET fails
     * at startup with a clear message instead of at the first login attempt
     * with an opaque one.
     */
    private static final int MIN_SECRET_BYTES = 32;

    private final Key key;
    private final long expirationMs;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-ms}") long expirationMs,
            // Whether this instance is running in production. Defaults to
            // FALSE so local dev and CI keep working untouched; production
            // sets APP_ENVIRONMENT=production (see DEPLOYMENT.md).
            @Value("${app.production:false}") boolean production) {

        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET is not set. Set it to a random 64+ character string.");
        }

        if (production) {
            if (DEV_FALLBACK_SECRET.equals(secret)
                    || PlaceholderValues.isSecretPlaceholder(secret)) {
                throw new IllegalStateException(
                        "Refusing to start in production with a missing, published, or CHANGE_ME JWT secret. "
                                + "Set JWT_SECRET to a real random 64+ character value.");
            }
            if (secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
                throw new IllegalStateException(
                        "JWT_SECRET is too short for HS256 - it must be at least "
                                + MIN_SECRET_BYTES + " bytes (use a random 64+ character string).");
            }
        }

        this.key = Keys.hmacShaKeyFor(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    public String generateToken(Long customerId, String email, Role role) {
        return Jwts.builder()
                .setSubject(email)
                .claim("customerId", customerId)
                .claim("role", role.name())
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    private Claims extractClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public String extractEmail(String token) {
        return extractClaims(token).getSubject();
    }

    public Long extractCustomerId(String token) {
        return extractClaims(token).get("customerId", Long.class);
    }

    public String extractRole(String token) {
        return extractClaims(token).get("role", String.class);
    }

    public boolean isTokenValid(String token) {
        try {
            extractClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Verifies the signature and parses claims exactly ONCE, returning them
     * (or null if invalid/expired/malformed) for the caller to read email/
     * customerId/role from directly. JwtFilter previously called
     * isTokenValid() + extractEmail() + extractCustomerId() + extractRole()
     * on every authenticated request - each one independently re-running the
     * full HMAC signature verification via parseClaimsJws, i.e. 4x the actual
     * cryptographic work needed per request for no benefit. This is the
     * preferred entry point for anything that needs more than one claim from
     * the same token; the individual extract-star/isTokenValid methods above are
     * left as-is for any single-claim caller that doesn't need this.
     */
    public Claims parseClaimsIfValid(String token) {
        try {
            return extractClaims(token);
        } catch (Exception e) {
            return null;
        }
    }
}
