package com.gpstore.dto;

import com.gpstore.exception.BadRequestException;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SendOtpRequest {

    private String mobileNumber;
    private String email;

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
