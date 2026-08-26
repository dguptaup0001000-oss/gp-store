package com.gpstore.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Body for {@code DELETE /api/customers/me}.
 *
 * Current password is the step-up: a stolen access token plus the word
 * DELETE typed in a dialog is not enough to destroy the account. The
 * field is not {@code @NotBlank} so OTP-only accounts (no password yet)
 * receive the service's "set a password first" message instead of a
 * generic validation error.
 */
@Getter
@Setter
public class DeleteAccountRequest {

    private String currentPassword;
}
