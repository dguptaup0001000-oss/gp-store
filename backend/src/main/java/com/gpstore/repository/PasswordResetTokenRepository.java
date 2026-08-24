package com.gpstore.repository;

import com.gpstore.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update PasswordResetToken t set t.consumedAt = :now "
            + "where t.customer.id = :customerId and t.consumedAt is null")
    int consumeAllOpenForCustomer(@Param("customerId") Long customerId, @Param("now") LocalDateTime now);
}
