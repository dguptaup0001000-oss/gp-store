package com.gpstore.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.gpstore.exception.BadRequestException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PhoneOtpVerifyRequest {

    @JsonProperty("phone")
    @JsonAlias({"mobileNumber", "mobile_number"})
    private String phone;

    @JsonProperty("email")
    private String email;

    @NotBlank(message = "OTP is required")
    @Pattern(regexp = "^\\d{6}$", message = "OTP must be 6 digits")
    private String otp;

    public String identity() {
        if (email != null && !email.isBlank()) {
            return email.trim();
        }
        if (phone != null && !phone.isBlank()) {
            return phone.trim();
        }
        throw new BadRequestException("Email is required");
    }
}
