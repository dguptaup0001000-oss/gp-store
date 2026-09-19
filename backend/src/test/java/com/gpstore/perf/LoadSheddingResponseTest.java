package com.gpstore.perf;

import com.gpstore.exception.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheOperationInvoker;
import org.springframework.mock.web.MockHttpServletRequest;

import java.sql.SQLTransientConnectionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a customer gets when the shop genuinely has no capacity left.
 *
 * THE POINT IS THAT THIS IS NOT HIDDEN. A request that could not get a
 * database connection did not succeed and must not be reported as though it
 * did. The question this test settles is only which honest answer it gets:
 * a 500, which says "this application is broken and there is nothing you can
 * usefully do", or a 503 with Retry-After, which says "this application is
 * full, try again shortly" - and which a client, a proxy and a load test all
 * already know how to read.
 *
 * The failure is still a failure. It is still logged. It is still counted by
 * http.server.requests as a non-2xx. Nothing here turns a broken request into
 * a successful one, and nothing here relaxes a threshold; a load test that
 * sees these has still found the ceiling, and now it can name it.
 */
@DisplayName("Pool exhaustion is reported honestly, as backpressure")
class LoadSheddingResponseTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private HttpServletRequest request() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/api/products");
        req.setRequestURI("/v1/api/products");
        return req;
    }

    @Test
    @DisplayName("a request that could not get a connection answers 503, not 200 and not 500")
    void poolExhaustionIsA503() {
        ResponseEntity<?> response = handler.handlePoolExhausted(
                new SQLTransientConnectionException("HikariPool-1 - Connection is not available, "
                        + "request timed out after 5000ms"),
                request());

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode(),
                "A request that never reached the database must not be reported as anything but a failure.");
        assertNotEquals(HttpStatus.OK, response.getStatusCode(),
                "Returning 200 here would hide the exact failure the load test exists to find.");
    }

    @Test
    @DisplayName("the 503 tells the caller when to come back")
    void retryAfterIsSet() {
        ResponseEntity<?> response = handler.handlePoolExhausted(
                new SQLTransientConnectionException("timed out"), request());

        String retryAfter = response.getHeaders().getFirst("Retry-After");
        assertNotNull(retryAfter, "Without Retry-After a client has to guess, and guessing is a retry storm.");
        assertTrue(Integer.parseInt(retryAfter) > 0);
    }

    @Test
    @DisplayName("the same answer for the Spring wrappers of the same failure")
    void springWrappedFailuresAreTreatedIdentically() {
        // Inside a @Transactional method the acquisition timeout never
        // surfaces as SQLTransientConnectionException - Spring has already
        // wrapped it by the time any handler sees it. Handling only the raw
        // form would have meant the fix worked on read paths and quietly did
        // not on every transactional one.
        for (Exception ex : new Exception[]{
                new org.springframework.transaction.CannotCreateTransactionException("no connection"),
                new org.springframework.jdbc.CannotGetJdbcConnectionException("no connection"),
                // The JPA repository path, and therefore most of the
                // application: Hibernate wraps the timeout and Spring
                // translates it to this before any handler is consulted.
                new org.springframework.dao.DataAccessResourceFailureException("no connection")}) {

            ResponseEntity<?> response = handler.handlePoolExhausted(ex, request());
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode(),
                    ex.getClass().getSimpleName() + " must be shed the same way as the raw JDBC form.");
        }
    }

    @Test
    @DisplayName("a cached endpoint sheds like every other endpoint")
    void poolExhaustionInsideACacheLoaderIsAlsoA503() {
        // THE SHAPE THE LOAD RUN ACTUALLY PRODUCED. Spring's cache
        // interceptor puts a ThrowableWrapper between the
        // ValueRetrievalException and the failure, so a handler that peels
        // one layer finds plumbing and falls through to 500. That is what
        // /api/products/feed and /api/products/search/instant did at two
        // thousand shops while every uncached endpoint in the same second
        // answered an honest 503.
        Throwable real = new org.springframework.transaction.CannotCreateTransactionException(
                "Could not open JPA EntityManager for transaction",
                new SQLTransientConnectionException("HikariPool-1 - Connection is not available"));
        Cache.ValueRetrievalException ex = new Cache.ValueRetrievalException(
                "shop:920", () -> null,
                new CacheOperationInvoker.ThrowableWrapper(real));

        ResponseEntity<?> response = handler.handleCacheLoadFailure(ex, request());

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode(),
                "A cached read that could not get a connection is full, not broken - "
                        + "500 tells a client retrying is pointless, which is not true.");
        assertNotNull(response.getHeaders().getFirst("Retry-After"),
                "The shed answer keeps its Retry-After no matter which layer produced it.");
    }

    @Test
    @DisplayName("a genuine loader failure is still a 500")
    void anythingElseInsideACacheLoaderStaysA500() {
        // The widening must not swallow real faults. Only "no connection"
        // becomes backpressure; a NullPointerException in a loader is a bug
        // and has to keep saying so.
        Cache.ValueRetrievalException ex = new Cache.ValueRetrievalException(
                "shop:920", () -> null,
                new CacheOperationInvoker.ThrowableWrapper(new IllegalStateException("bug")));

        ResponseEntity<?> response = handler.handleCacheLoadFailure(ex, request());

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode(),
                "Reporting a broken loader as backpressure would hide it behind a retry.");
    }

    @Test
    @DisplayName("the domain exceptions keep their own status through the wrapper")
    void domainFailuresSurviveTheWrapper() {
        // These three were the reason handleCacheLoadFailure exists. They were
        // being checked one layer above where they actually sit, so they only
        // worked when no wrapper was in the way.
        Cache.ValueRetrievalException badRequest = new Cache.ValueRetrievalException(
                "k", () -> null,
                new CacheOperationInvoker.ThrowableWrapper(
                        new com.gpstore.exception.BadRequestException("Search keyword is required")));

        assertEquals(HttpStatus.BAD_REQUEST,
                handler.handleCacheLoadFailure(badRequest, request()).getStatusCode(),
                "A blank keyword is the caller's mistake at any nesting depth.");
    }

    @Test
    @DisplayName("the customer is not shown the pool's internals")
    void theMessageIsForACustomer() {
        ResponseEntity<?> response = handler.handlePoolExhausted(
                new SQLTransientConnectionException("HikariPool-1 - Connection is not available, "
                        + "request timed out after 5000ms (total=10, active=10, idle=0, waiting=37)"),
                request());

        String body = String.valueOf(response.getBody());
        assertFalse(body.contains("HikariPool"), "The pool's name and counts are operational detail, not customer copy.");
        assertFalse(body.toLowerCase().contains("connection is not available"),
                "Internal exception text must not be echoed to a client.");
    }
}
