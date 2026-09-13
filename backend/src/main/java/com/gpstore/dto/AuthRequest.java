package com.gpstore.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AuthRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid")
    private String email;

    @NotBlank(message = "Password is required")
    private String password;

    /**
     * The fifteen characters that claim a never-used account.
     *
     * OPTIONAL, AND THAT IS THE DESIGN (§28). It is required on the ONE login
     * that claims an account holding an unspent code, and asked for never
     * again. A field that were mandatory would make this a permanent third
     * password and train merchants to keep it written down next to the phone -
     * which is the opposite of what a second factor is for.
     *
     * Not validated here: whether this account needs one is a server-side
     * question about the account, not a shape the client can be trusted to
     * know.
     */
    private String activationCode;
}
