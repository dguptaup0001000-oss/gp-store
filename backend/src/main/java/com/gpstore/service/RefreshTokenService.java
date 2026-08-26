package com.gpstore.service;

import com.gpstore.entity.Customer;
import com.gpstore.entity.RefreshToken;
import com.gpstore.exception.AuthException;
import com.gpstore.repository.RefreshTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class RefreshTokenService {

    private static final String INVALID_REFRESH_TOKEN = "Invalid or expired refresh token";

    private final RefreshTokenRepository repository;
    private final SecureRandom secureRandom = new SecureRandom();
    private final long expirationMs;

    public RefreshTokenService(
            RefreshTokenRepository repository,
            @Value("${refresh.token.expiration-ms}") long expirationMs) {
        this.repository = repository;
        this.expirationMs = expirationMs;
    }

    /** Creates a new refresh token for this customer and returns the RAW token (shown once, never stored). */
    @Transactional
    public String issue(Customer customer) {
        return issue(customer, UUID.randomUUID().toString());
    }

    private String issue(Customer customer, String familyId) {
        byte[] randomBytes = new byte[64];
        secureRandom.nextBytes(randomBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        RefreshToken entity = new RefreshToken();
        entity.setTokenHash(hash(rawToken));
        entity.setCustomer(customer);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setExpiresAt(LocalDateTime.now().plusNanos(expirationMs * 1_000_000));
        entity.setRevoked(false);
        entity.setFamilyId(familyId);

        repository.save(entity);

        return rawToken;
    }

    /**
     * Validates a raw refresh token and rotates it: the old one is revoked and a
     * new one issued in the same family.
     *
     * Replaying a revoked token is treated as theft. Every live token in that
     * family is revoked so the attacker cannot keep the rotated successor, and
     * the legitimate device must log in again. The error text is the same as
     * an expired token so this endpoint does not become an oracle.
     *
     * AuthException must not roll this transaction back: the family revoke
     * has to commit before the caller sees the error, or the successor stays
     * live and reuse detection is theatre.
     */
    @Transactional(noRollbackFor = AuthException.class)
    public RotationResult validateAndRotate(String rawToken) {
        RefreshToken existing = repository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new AuthException(INVALID_REFRESH_TOKEN));

        if (Boolean.TRUE.equals(existing.getRevoked())) {
            if (existing.getFamilyId() != null && !existing.getFamilyId().isBlank()) {
                repository.revokeAllForFamily(existing.getFamilyId());
            } else if (existing.getCustomer() != null) {
                repository.revokeAllForCustomer(existing.getCustomer().getId());
            }
            throw new AuthException(INVALID_REFRESH_TOKEN);
        }

        if (existing.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new AuthException(INVALID_REFRESH_TOKEN);
        }

        existing.setRevoked(true);
        repository.save(existing);

        Customer customer = existing.getCustomer();
        if (!com.gpstore.security.CustomerAccountStatusService.isCustomerUsable(customer)) {
            repository.revokeAllForCustomer(customer.getId());
            throw new AuthException(INVALID_REFRESH_TOKEN);
        }
        String familyId = existing.getFamilyId();
        if (familyId == null || familyId.isBlank()) {
            familyId = UUID.randomUUID().toString();
            existing.setFamilyId(familyId);
            repository.save(existing);
        }
        String newRawToken = issue(customer, familyId);

        return new RotationResult(customer, newRawToken);
    }

    @Transactional
    public void revoke(String rawToken) {
        repository.findByTokenHashAndRevokedFalse(hash(rawToken))
                .ifPresent(token -> {
                    token.setRevoked(true);
                    repository.save(token);
                });
        // Intentionally no error if not found/already revoked - logout should be idempotent.
    }

    @Transactional
    public void revokeAllForCustomer(Long customerId) {
        repository.revokeAllForCustomer(customerId);
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public record RotationResult(Customer customer, String newRawToken) {}
}
