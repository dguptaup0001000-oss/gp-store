package com.gpstore.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), req);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiError> handleBadRequest(BadRequestException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), req);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), req);
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ApiError> handleAuth(AuthException ex, HttpServletRequest req) {
        return build(HttpStatus.UNAUTHORIZED, ex.getMessage(), req);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, "You do not have permission to perform this action", req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fe.getField(), fe.getDefaultMessage());
        }
        Map<String, Object> body = new HashMap<>();
        body.put("status", HttpStatus.BAD_REQUEST.value());
        body.put("error", "Validation Failed");
        body.put("fieldErrors", fieldErrors);
        body.put("path", req.getRequestURI());
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * A database constraint said no. That is a 409, not a 500.
     *
     * Without this, a duplicate email at registration, a repeated
     * idempotency key or a colliding SKU all landed in the catch-all below
     * and were reported as "An unexpected error occurred" with status 500.
     * Three things were wrong with that:
     *
     *   - It tells the customer the shop is broken when in fact their
     *     request conflicted with something that already exists, which is
     *     information they can act on.
     *   - It buries real bugs. If genuine 500s and routine duplicate
     *     registrations arrive in the same bucket, the bucket stops being
     *     worth reading.
     *   - 5xx invites retries from clients and proxies that treat 4xx as
     *     final. Retrying a request that violated a unique constraint can
     *     never succeed.
     *
     * THE MESSAGE IS DELIBERATELY GENERIC. ex.getMessage() on a Postgres
     * constraint violation contains the constraint name, the table, the
     * column and the conflicting VALUE - so returning it would leak both the
     * schema and, for a duplicate-email check, confirm that a given address
     * is already registered. Logged at WARN for diagnosis, never returned.
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(
            org.springframework.dao.DataIntegrityViolationException ex, HttpServletRequest req) {
        log.warn("Constraint violation on {} {}: {}",
                req.getMethod(), req.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return build(HttpStatus.CONFLICT,
                "That conflicts with something that already exists. Please check and try again.", req);
    }

    // Catch-all: never leak internal exception messages/stack traces to the client,
    // but this is the only handler for genuinely unanticipated failures, so it must
    // log the real exception - otherwise a bug that lands here leaves no trace
    // anywhere to diagnose it from.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", req);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message, HttpServletRequest req) {
        ApiError body = new ApiError(status.value(), status.getReasonPhrase(), message, req.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
