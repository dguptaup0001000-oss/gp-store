package com.gpstore.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.gpstore.exception.BadRequestException;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PhoneOtpRequest {

    @JsonProperty("phone")
    @JsonAlias({"mobileNumber", "mobile_number"})
    private String phone;

    @JsonProperty("email")
    private String email;

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
