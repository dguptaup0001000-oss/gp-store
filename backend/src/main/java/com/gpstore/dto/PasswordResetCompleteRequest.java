package com.gpstore.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PasswordResetCompleteRequest {

    @NotBlank(message = "Reset token is required")
    @JsonProperty("reset_token")
    @JsonAlias("resetToken")
    private String resetToken;

    @NotBlank(message = "New password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    @JsonProperty("new_password")
    @JsonAlias("newPassword")
    private String newPassword;
}
