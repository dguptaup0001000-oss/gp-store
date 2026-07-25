package com.gpstore.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChangePasswordRequest {

    // Deliberately NOT @NotBlank - null/blank is valid here (an OTP-only
    // account setting up password login for the first time has no current
    // password to provide). AuthService.changePassword handles both cases.
    private String currentPassword;

    @NotBlank(message = "New password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String newPassword;
}
