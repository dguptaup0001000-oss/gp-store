package com.gpstore.service;

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

    private final Key key;
    private final long expirationMs;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-ms}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes());
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
