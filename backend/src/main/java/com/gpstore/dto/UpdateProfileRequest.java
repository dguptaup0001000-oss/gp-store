package com.gpstore.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Body for {@code PUT /api/customers/me}.
 *
 * {@code currentPassword} is required only when the mobile number actually
 * changes. A stolen JWT must not be enough to re-bind the account to an
 * attacker's phone (the OTP-login takeover path). Name and first-time
 * email add do not need the password.
 */
@Getter
@Setter
public class UpdateProfileRequest {

    private String fullName;
    private String mobileNumber;
    private String email;
    private String currentPassword;
}
