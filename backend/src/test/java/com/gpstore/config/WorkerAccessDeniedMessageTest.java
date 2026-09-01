package com.gpstore.config;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The sentence a refused worker is given.
 *
 * WHAT WAS BROKEN. A rider typed their Gmail address into the worker app and
 * got "You don't have permission to do that." Their password was correct;
 * /api/auth/login had already succeeded. What failed was /api/worker/me,
 * because their account was not a delivery worker - and the message named
 * neither the cause nor anyone who could fix it, so the obvious reading was
 * "wrong password" and the obvious next move was to retype it.
 *
 * DeliveryWorkerController has a better sentence for the next failure along,
 * but Spring refuses in the filter chain, so the controller never runs and
 * that sentence never reached anybody.
 */
@DisplayName("A refused worker is told what is actually wrong")
class WorkerAccessDeniedMessageTest {

    private static HttpServletRequest request(String contextPath, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContextPath(contextPath);
        request.setRequestURI(uri);
        return request;
    }

    @Test
    @DisplayName("the worker route names the control that fixes it")
    void workerRouteGetsTheSpecificMessage() {
        // The real production shape: the app runs under a /v1 context path,
        // and getRequestURI keeps it. Matching on the raw URI without
        // stripping that would silently never fire in production while
        // passing every test written against a bare path.
        String message = SecurityConfig.accessDeniedMessage(request("/v1", "/v1/api/worker/me"));

        assertEquals(SecurityConfig.WORKER_ACCESS_DENIED, message);
        assertTrue(message.contains("Worker app sign-in"),
                "It has to name the control an administrator actually looks for, or it is "
                        + "just a longer way of saying no.");
        assertFalse(message.contains("permission"),
                "'Permission' is what sent the rider back to retype a password that was right.");
    }

    @Test
    @DisplayName("every worker endpoint, not just /me")
    void allWorkerRoutesGetIt() {
        for (String uri : new String[]{
                "/v1/api/worker/me", "/v1/api/worker/tasks", "/v1/api/worker/orders/12/status"}) {
            assertEquals(SecurityConfig.WORKER_ACCESS_DENIED,
                    SecurityConfig.accessDeniedMessage(request("/v1", uri)), uri);
        }
    }

    @Test
    @DisplayName("no context path configured still matches")
    void worksWithoutAContextPath() {
        assertEquals(SecurityConfig.WORKER_ACCESS_DENIED,
                SecurityConfig.accessDeniedMessage(request("", "/api/worker/me")));
    }

    @Test
    @DisplayName("admin and customer refusals stay generic")
    void everythingElseStaysGeneric() {
        // Deliberate: a refusal that described the missing permission would
        // tell whoever probed these what to go looking for.
        for (String uri : new String[]{
                "/v1/api/delivery-partners/7/login-account",
                "/v1/api/admin/analytics",
                "/v1/api/orders/3",
                "/v1/actuator/env"}) {
            assertEquals(SecurityConfig.GENERIC_ACCESS_DENIED,
                    SecurityConfig.accessDeniedMessage(request("/v1", uri)), uri);
        }
    }

    @Test
    @DisplayName("a path that merely contains the worker segment is not a worker route")
    void doesNotMatchOnASubstring() {
        assertEquals(SecurityConfig.GENERIC_ACCESS_DENIED,
                SecurityConfig.accessDeniedMessage(request("/v1", "/v1/api/admin/api/worker/me")));
    }

    @Test
    @DisplayName("the message is safe to drop into hand-built JSON")
    void survivesTheUnescapedJsonBody() {
        // The handler concatenates this into a JSON string literal with no
        // escaping. A double quote or backslash would produce a body the app
        // cannot parse - which is how a clear message becomes a worse one.
        for (String message : new String[]{
                SecurityConfig.WORKER_ACCESS_DENIED, SecurityConfig.GENERIC_ACCESS_DENIED}) {
            assertFalse(message.contains("\""), message);
            assertFalse(message.contains("\\"), message);
            assertFalse(message.contains("\n"), message);
        }
    }

    @Test
    @DisplayName("a null URI does not blow up the handler")
    void nullUriFallsBackInsteadOfThrowing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(null);
        // Throwing here would replace a 403 with a 500 from inside the error
        // handler itself.
        assertEquals(SecurityConfig.GENERIC_ACCESS_DENIED,
                SecurityConfig.accessDeniedMessage(request));
    }
}
