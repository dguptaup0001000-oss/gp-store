package com.gpstore.exception;

/** Thrown for authentication failures. Message is intentionally generic
 *  everywhere it's used, to avoid leaking whether an email exists. */
public class AuthException extends RuntimeException {
    public AuthException(String message) {
        super(message);
    }
}
