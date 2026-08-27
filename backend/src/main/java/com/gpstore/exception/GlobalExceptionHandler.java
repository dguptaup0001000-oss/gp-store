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

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ApiError> handleTooManyRequests(TooManyRequestsException ex, HttpServletRequest req) {
        return build(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), req);
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
     * A database constraint said no. That is a 4xx, not a 500 - but WHICH 4xx,
     * and whether it is the caller's fault at all, depends entirely on which
     * constraint fired.
     *
     * Without this, a duplicate email at registration, a repeated idempotency
     * key or a colliding SKU all landed in the catch-all below and were
     * reported as "An unexpected error occurred" with status 500. Three things
     * were wrong with that:
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
     * WHY THIS IS NO LONGER ONE ANSWER FOR ALL OF THEM. Collapsing every
     * DataIntegrityViolationException into "that conflicts with something that
     * already exists" cost a working day. Admin "Add Product" returned that
     * message for every product, including brand-new names - and there is no
     * unique constraint on products.name at all. The real conflict was on
     * products_pkey: the identity sequence had fallen behind the table (see
     * V16__resync_identity_sequences.sql and IdentitySequenceGuard), so the
     * application was generating an id a row already held. A server-side
     * defect was being reported as bad input, and the message sent whoever
     * read it looking for a duplicate product that never existed.
     *
     * DataIntegrityViolationException is not a synonym for "duplicate". It
     * also covers NOT NULL, foreign key and CHECK violations, which are three
     * different problems with three different fixes, and a primary-key
     * collision on a generated id, which is not the caller's problem at all.
     *
     * THE MESSAGES STAY GENERIC ABOUT VALUES. getMostSpecificCause() on a
     * Postgres constraint violation contains the constraint name, the table,
     * the column and the conflicting VALUE - so returning it would leak both
     * the schema and, for a duplicate-email check, confirm that a given
     * address is already registered. Logged for diagnosis, never returned.
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(
            org.springframework.dao.DataIntegrityViolationException ex, HttpServletRequest req) {

        String sqlState = sqlStateOf(ex);
        String constraint = constraintNameOf(ex);
        String cause = ex.getMostSpecificCause().getMessage();

        // A primary-key collision. Nothing in this application ever sets an id
        // - every @Id is @GeneratedValue - so the id in the failing INSERT came
        // from the database's own sequence, and the sequence handed out one
        // that was already taken. That is ours, not the caller's, and it must
        // not be logged at WARN alongside routine duplicate registrations.
        if (UNIQUE_VIOLATION.equals(sqlState) && isPrimaryKey(constraint, cause)) {
            log.error("PRIMARY KEY COLLISION on {} {}: {}. The id came from the database's own identity sequence, so "
                    + "that sequence has fallen behind its table - rows were inserted with explicit ids at some "
                    + "point (a restored dump, a CSV import, or an INSERT typed into a SQL console). Every insert "
                    + "into this table keeps failing until it is re-synced. IdentitySequenceGuard repairs this on "
                    + "the next restart; V16__resync_identity_sequences.sql repairs it on the next deploy.",
                    req.getMethod(), req.getRequestURI(), cause);
            return build(HttpStatus.INTERNAL_SERVER_ERROR,
                    "We couldn't assign an id to that record. This is a fault on our side, not a problem with what "
                            + "you entered - please try again, and tell an administrator if it keeps happening.", req);
        }

        log.warn("Constraint violation on {} {} (sqlState={}, constraint={}): {}",
                req.getMethod(), req.getRequestURI(), sqlState, constraint, cause);

        if (NOT_NULL_VIOLATION.equals(sqlState)) {
            return build(HttpStatus.BAD_REQUEST,
                    "A required field was missing. Please fill in everything the form asks for and try again.", req);
        }
        if (FOREIGN_KEY_VIOLATION.equals(sqlState)) {
            return build(HttpStatus.BAD_REQUEST,
                    "That refers to something that no longer exists. Please refresh and try again.", req);
        }
        if (CHECK_VIOLATION.equals(sqlState)) {
            return build(HttpStatus.BAD_REQUEST,
                    "One of those values isn't allowed. Please check them and try again.", req);
        }

        return build(HttpStatus.CONFLICT,
                "That conflicts with something that already exists. Please check and try again.", req);
    }

    // Postgres SQLSTATE class 23 - integrity constraint violation.
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String NOT_NULL_VIOLATION = "23502";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String CHECK_VIOLATION = "23514";

    /**
     * SQLSTATE off the first SQLException in the cause chain. Walked rather
     * than read off getMostSpecificCause() directly, because the most specific
     * cause is not guaranteed to be the SQLException - Hibernate wraps it, and
     * some drivers wrap it again.
     */
    private static String sqlStateOf(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }

    /** Hibernate knows the constraint name; the raw driver message only spells it. */
    private static String constraintNameOf(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException cve) {
                return cve.getConstraintName();
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }

    /**
     * Postgres names a primary key constraint "&lt;table&gt;_pkey" by default,
     * and every table here took that default. The message is checked too
     * because getConstraintName() comes back null often enough that relying on
     * it alone would silently misclassify the very case this exists to catch.
     */
    private static boolean isPrimaryKey(String constraint, String causeMessage) {
        if (constraint != null && constraint.endsWith("_pkey")) {
            return true;
        }
        return causeMessage != null && causeMessage.contains("_pkey");
    }

    /**
     * A route that does not exist is a 404. It reached this class at all only
     * because the catch-all below was swallowing Spring's own
     * NoResourceFoundException and relabelling it a server fault.
     *
     * THIS COST SOMEBODY AN EVENING. A call to GET /v1/api/catalog/products -
     * a path this application has never had - answered:
     *
     *     500 {"message":"An unexpected error occurred"}
     *
     * which reads as "the server is broken, not your fault, try later". The
     * reasonable next step is to go looking for the stack trace in the
     * production logs. There was no stack trace. There was no exception.
     * There was a typo in a URL, and the error handler had disguised it as an
     * outage.
     *
     * 404 says "that address does not exist, check it" - which is both true
     * and immediately actionable.
     *
     * The path is echoed back because it is the single most useful thing to
     * know here, and it contains nothing sensitive: the caller just sent it.
     * No message from the exception is used - NoResourceFoundException's text
     * names internal resource resolution details that are of no use to a
     * client.
     *
     * Logged at DEBUG, not WARN: a 404 is routine traffic. Logging every
     * scan of /wp-login.php at WARN would bury real problems.
     */
    @ExceptionHandler({
            org.springframework.web.servlet.resource.NoResourceFoundException.class,
            org.springframework.web.servlet.NoHandlerFoundException.class
    })
    public ResponseEntity<ApiError> handleNoHandler(Exception ex, HttpServletRequest req) {
        log.debug("No handler for {} {}", req.getMethod(), req.getRequestURI());
        return build(HttpStatus.NOT_FOUND,
                "No endpoint exists at " + req.getRequestURI(), req);
    }

    /**
     * The wrong HTTP verb on a real path is a 405, not a 500 - and the
     * distinction is the same kind of useful. POST to a GET-only endpoint
     * should say so rather than implying the server fell over.
     */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(
            org.springframework.web.HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        log.debug("Method {} not supported for {}", req.getMethod(), req.getRequestURI());
        return build(HttpStatus.METHOD_NOT_ALLOWED,
                req.getMethod() + " is not supported for this endpoint", req);
    }

    /**
     * The database pool had nothing left to give within its timeout.
     *
     * WHY THIS IS 503 AND NOT 500. A 500 says "this request hit a bug"; a
     * caller retries it and gets the same bug. A 503 with Retry-After says
     * "this server is over capacity right now", which is both TRUE and
     * actionable - the client backs off, and a load balancer or monitor can
     * tell overload apart from breakage. Under the load test this is the
     * difference between a wall of indistinguishable 5xx and a signal that
     * says exactly which resource ran out.
     *
     * IT IS NOT HIDING A FAILURE. The request genuinely did not succeed and
     * the caller is told so. What changes is that the answer is honest about
     * WHY, and that it arrives in seconds instead of the caller waiting out a
     * long acquisition timeout holding a request thread the server needs.
     *
     * Logged at warn rather than error, and without a stack trace: at
     * saturation this fires thousands of times a minute, and thousands of
     * identical stack traces is how a log stops being readable at the exact
     * moment somebody needs to read it.
     */
    /*
     * FOUR TYPES, because the same failure arrives wearing four different
     * names depending on where in the stack it happened, and catching only
     * the obvious one would have meant this worked on some endpoints and
     * silently did not on others:
     *
     *   SQLTransientConnectionException  - Hikari's own timeout, raw.
     *   CannotCreateTransactionException - the same thing hit while Spring
     *                                      was opening a @Transactional.
     *   CannotGetJdbcConnectionException - the same thing on a JdbcTemplate.
     *   DataAccessResourceFailureException - the same thing after Hibernate
     *                                      wrapped it and Spring translated
     *                                      it, which is the JPA repository
     *                                      path and therefore most of the
     *                                      application.
     */
    @ExceptionHandler({
            java.sql.SQLTransientConnectionException.class,
            org.springframework.transaction.CannotCreateTransactionException.class,
            org.springframework.jdbc.CannotGetJdbcConnectionException.class,
            org.springframework.dao.DataAccessResourceFailureException.class
    })
    public ResponseEntity<ApiError> handlePoolExhausted(Exception ex, HttpServletRequest req) {
        log.warn("Database connection pool exhausted serving {} {}: {}",
                req.getMethod(), req.getRequestURI(), ex.getMessage());
        ApiError body = new ApiError(HttpStatus.SERVICE_UNAVAILABLE.value(),
                HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase(),
                "The shop is very busy right now. Please try again in a moment.",
                req.getRequestURI());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "2")
                .body(body);
    }

    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(
            org.springframework.web.bind.MissingServletRequestParameterException ex,
            HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), req);
    }

    /**
     * {@code @Cacheable(sync = true)} wraps the loader's exception in
     * {@link org.springframework.cache.Cache.ValueRetrievalException}. Without
     * this handler a blank search keyword becomes HTTP 500 instead of 400,
     * which is exactly the "hide a 400 behind a 500" failure the shop had
     * on {@code /search/instant}.
     */
    @ExceptionHandler(org.springframework.cache.Cache.ValueRetrievalException.class)
    public ResponseEntity<ApiError> handleCacheLoadFailure(
            org.springframework.cache.Cache.ValueRetrievalException ex,
            HttpServletRequest req) {
        Throwable cause = ex.getCause();
        if (cause instanceof BadRequestException bad) {
            return handleBadRequest(bad, req);
        }
        if (cause instanceof ResourceNotFoundException missing) {
            return handleNotFound(missing, req);
        }
        if (cause instanceof ConflictException conflict) {
            return handleConflict(conflict, req);
        }
        log.error("Cache loader failed on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", req);
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
