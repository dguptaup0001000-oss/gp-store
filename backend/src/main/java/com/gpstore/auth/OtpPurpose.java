package com.gpstore.auth;

/**
 * OTP challenges are purpose-scoped. A LOGIN code must never satisfy
 * PASSWORD_RESET, and the reverse is also refused.
 */
public enum OtpPurpose {
    LOGIN,
    PASSWORD_RESET
}
