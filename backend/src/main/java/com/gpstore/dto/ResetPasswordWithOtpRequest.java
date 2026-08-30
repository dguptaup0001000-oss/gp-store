package com.gpstore.dto;

import com.gpstore.exception.BadRequestException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ResetPasswordWithOtpRequest {

    private String mobileNumber;
    private String email;

    @NotBlank(message = "OTP is required")
    @Pattern(regexp = "^\\d{6}$", message = "OTP must be 6 digits")
    private String otp;

    @NotBlank(message = "New password is required")
    @Size(min = 10, message = "Password must be at least 10 characters")
    private String newPassword;

    public String identity() {
        if (email != null && !email.isBlank()) {
            return email.trim();
        }
        if (mobileNumber != null && !mobileNumber.isBlank()) {
            return mobileNumber.trim();
        }
        throw new BadRequestException("Email is required");
    }
}
