package com.gpstore.entity;

import com.gpstore.auth.OtpPurpose;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "otp_verifications")
public class OtpVerification {

    public static final String PROVIDER_MANAGED_HASH = "PROVIDER_MANAGED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String mobileNumber;

    /**
     * SHA-256 of the 6-digit code for mock/local challenges. When MSG91
     * generates the code, this is {@link #PROVIDER_MANAGED_HASH} — never a
     * plaintext OTP.
     */
    @Column(nullable = false)
    private String otpHash;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private Integer attempts = 0;

    private Boolean verified = false;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OtpPurpose purpose = OtpPurpose.LOGIN;

    private LocalDateTime consumedAt;

    @Column(length = 128)
    private String providerReference;

    private Integer resendCount = 0;

    private LocalDateTime lastSentAt;
}
