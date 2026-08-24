package com.gpstore.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PhoneOtpRequest {

    @NotBlank(message = "Phone number is required")
    @JsonProperty("phone")
    @JsonAlias({"mobileNumber", "mobile_number"})
    private String phone;
}
