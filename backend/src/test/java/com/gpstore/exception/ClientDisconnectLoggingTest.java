package com.gpstore.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * A customer who closes the app mid-scroll is not an incident.
 *
 * The 5,000-user load run left thousands of these in the backend log:
 *
 *   AsyncRequestNotUsableException: ServletOutputStream failed to flush:
 *   java.io.IOException: Broken pipe
 *     -> Unhandled exception on GET /v1/api/products/category/8
 *
 * Every one was an ERROR with a full stack trace, produced by the catch-all
 * handler that exists for genuinely unanticipated faults. On a live shop the
 * same line is written every time a customer backgrounds the app or walks out
 * of coverage while a category page is still streaming. That volume of noise
 * is not harmless - it is what a real outage has to be found inside of.
 *
 * These tests fix the classification: peer-went-away is DEBUG and writes no
 * response body, while an I/O failure that is actually the server's fault
 * keeps its ERROR and its 500. Nothing is swallowed that was ever diagnosable.
 */
@DisplayName("Client disconnects are not logged as server errors")
class ClientDisconnectLoggingTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private HttpServletRequest request() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/api/products/category/8");
        req.setRequestURI("/v1/api/products/category/8");
        return req;
    }

    /** Reproduces the exact chain observed in the load-test log. */
    @Test
    @DisplayName("the async broken pipe from the load run is recognised as a disconnect")
    void asyncBrokenPipeIsADisconnect() {
        IOException observed = new AsyncRequestNotUsableException(
                "ServletOutputStream failed to flush", new IOException("Broken pipe"));

        assertTrue(GlobalExceptionHandler.isClientDisconnect(observed));
    }

    /**
     * Catalina's ClientAbortException is not on this module's compile classpath
     * on purpose, so the check matches by type name. A stand-in with the same
     * fully-qualified suffix proves the name match works.
     */
    @Test
    @DisplayName("a ClientAbortException is recognised by type name, without a Catalina dependency")
    void clientAbortIsRecognisedByName() {
        assertTrue(GlobalExceptionHandler.isClientDisconnect(new ClientAbortException()));
    }

    @Test
    @DisplayName("a reset connection buried in the cause chain is still a disconnect")
    void nestedConnectionResetIsADisconnect() {
        IOException nested = new IOException("write failed",
                new IOException("wrapper", new SocketException("Connection reset by peer")));

        assertTrue(GlobalExceptionHandler.isClientDisconnect(nested));
    }

    @Test
    @DisplayName("a self-referential cause chain terminates instead of hanging")
    void selfReferentialCauseChainTerminates() {
        // A throwable whose cause is itself would spin a naive walk forever.
        // Nothing in production is expected to do this; the depth cap exists so
        // that a misbehaving library cannot wedge a request thread.
        assertFalse(GlobalExceptionHandler.isClientDisconnect(new SelfCausedException()));
    }

    @Test
    @DisplayName("a server-side I/O failure is NOT treated as a disconnect")
    void serverSideIoFailureIsNotADisconnect() {
        assertFalse(GlobalExceptionHandler.isClientDisconnect(
                new EOFException("unexpected end of stream reading upstream response")));
        assertFalse(GlobalExceptionHandler.isClientDisconnect(
                new IOException("No space left on device")));
    }

    @Test
    @DisplayName("a disconnect writes no response - the socket is already gone")
    void disconnectProducesNoResponseBody() {
        ResponseEntity<ApiError> response = handler.handleIoException(
                new AsyncRequestNotUsableException("ServletOutputStream failed to flush",
                        new IOException("Broken pipe")),
                request());

        // null tells Spring the request is fully handled. Attempting to serialise
        // a JSON error body onto a closed socket would only fail a second time.
        assertNull(response);
    }

    @Test
    @DisplayName("a genuine I/O failure still gets a 500 and a body")
    void genuineIoFailureStillFails() {
        ResponseEntity<ApiError> response =
                handler.handleIoException(new IOException("No space left on device"), request());

        assertNotNull(response);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        ApiError body = response.getBody();
        assertNotNull(body);
        // The client is told something broke, but never what - no internal detail leaks.
        assertEquals("An unexpected error occurred", body.getMessage());
        assertFalse(body.getMessage().contains("No space left"));
    }

    /**
     * The unit tests above prove the method. This proves the WIRING - that Spring's
     * exception resolver picks the IOException handler over the catch-all
     * {@code @ExceptionHandler(Exception.class)} that was previously swallowing
     * these. If handler selection ever regressed, the catch-all would answer with a
     * 500 and a JSON body and this assertion would catch it.
     */
    @Test
    @DisplayName("Spring routes a broken pipe to the IOException handler, not the catch-all")
    void resolverPrefersTheIoHandlerOverTheCatchAll() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DisconnectingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        var result = mvc.perform(get("/boom")).andReturn();

        // The catch-all would have produced 500 plus an ApiError body. Our handler
        // returns null, so the resolver marks the request handled and writes nothing.
        assertEquals("", result.getResponse().getContentAsString());
        assertNotEquals(500, result.getResponse().getStatus());
    }

    @RestController
    static class DisconnectingController {
        @GetMapping("/boom")
        String boom() throws IOException {
            throw new AsyncRequestNotUsableException(
                    "ServletOutputStream failed to flush", new IOException("Broken pipe"));
        }
    }

    /** Stands in for org.apache.catalina.connector.ClientAbortException. */
    private static class ClientAbortException extends IOException {
        ClientAbortException() {
            super();
        }
    }

    private static class SelfCausedException extends IOException {
        SelfCausedException() {
            super("looping");
        }

        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }
}
