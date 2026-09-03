package com.gpstore.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * What an admin may send when creating a customer - and nothing else.
 *
 * THIS REPLACED A RAW Customer ENTITY, and the two fields it does NOT have
 * are the whole point.
 *
 * NO role. saveCustomer defaulted the role to CUSTOMER only when it arrived
 * null, so a body carrying "role":"SUPER_ADMIN" was persisted verbatim, with
 * a password chosen in the same request. The route needs CUSTOMERS_MANAGE,
 * which MANAGER holds - a limited role with eighteen named permissions. It
 * was a login as the highest role in the system, one request away.
 *
 * NO id. repository.save() on an entity carrying an id is an UPDATE, so a
 * body naming somebody else's customer id overwrote that account - an
 * administrator's included - with a fresh password. A creation endpoint that
 * can rewrite an existing row is a takeover.
 *
 * Both are closed by there being no field to put them in, which is the only
 * version of this fix that cannot be undone by someone adding a line to a
 * service later.
 */
public class AdminCreateCustomerRequest {

    @NotBlank(message = "A customer needs a name.")
    @Size(max = 120)
    private String fullName;

    /** Optional: a phone-order customer may have no email at all. */
    @Email(message = "That email address is not valid.")
    @Size(max = 190)
    private String email;

    @NotBlank(message = "A customer needs a mobile number.")
    @Pattern(regexp = "\\d{10,15}", message = "A mobile number is 10 to 15 digits.")
    private String mobileNumber;

    /**
     * Optional, and hashed by the service. Blank means "they will log in with
     * an OTP later", which is how a phone-order account normally starts.
     */
    @Size(max = 100, message = "That password is too long.")
    private String password;

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getMobileNumber() { return mobileNumber; }
    public void setMobileNumber(String mobileNumber) { this.mobileNumber = mobileNumber; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
