package com.gpstore.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Only the SHA-256 hash is stored - the raw token is shown to the client
    // exactly once and can never be recovered from the database. Same principle
    // as a password: if the DB leaks, the tokens in it are useless.
    @Column(unique = true, nullable = false)
    private String tokenHash;

    @ManyToOne(fetch = FetchType.LAZY)
    private Customer customer;

    private LocalDateTime expiresAt;

    private Boolean revoked = false;

    private LocalDateTime createdAt;

    /**
     * Tokens issued by the same login/refresh chain share a family id.
     * Replaying a revoked token of the family revokes every live successor,
     * so a stolen refresh token that is used after rotation cannot keep a
     * second session alive.
     */
    @Column(length = 36)
    private String familyId;
}
