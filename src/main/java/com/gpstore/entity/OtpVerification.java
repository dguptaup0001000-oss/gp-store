package com.gpstore.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "otp_verifications")
public class OtpVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String mobileNumber;

    // Hashed, same principle as passwords/refresh tokens - never store the
    // real 6-digit code in plaintext, even though it's short-lived.
    @Column(nullable = false)
    private String otpHash;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    // Wrong-code attempts against THIS otp - capped to stop someone brute
    // forcing a 6-digit space (only 1 million possibilities) within the
    // few-minute validity window.
    private Integer attempts = 0;

    private Boolean verified = false;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
