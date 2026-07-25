package com.gpstore.repository;

import com.gpstore.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHashAndRevokedFalse(String tokenHash);

    // Used on logout-everywhere / password change - revokes every session for this customer.
    @Modifying
    @Query("update RefreshToken r set r.revoked = true where r.customer.id = :customerId and r.revoked = false")
    void revokeAllForCustomer(Long customerId);
}
