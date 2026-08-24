package com.gpstore.otp;

/**
 * Provider/transport failure. Callers must map this to a generic customer
 * message — never include the cause text in an API body.
 */
public class OtpProviderException extends RuntimeException {

    public OtpProviderException(String message) {
        super(message);
    }

    public OtpProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
