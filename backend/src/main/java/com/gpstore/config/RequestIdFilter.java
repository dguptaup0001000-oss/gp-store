package com.gpstore.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Gives every request an id, puts it in the logs, and returns it to the caller.
 *
 * WHAT THIS IS FOR. A customer says "my order failed at about half past
 * seven". Without a request id, finding that request in the log means
 * guessing from timestamps among everything else happening at the time. With
 * one, the customer's error screen shows an id, and that id appears on every
 * log line the request produced - including the ones written after the
 * response was sent.
 *
 * ORDERED FIRST, ahead of the rate limiter and the JWT filter, so that a
 * request rejected by either still carries an id. The requests you most need
 * to trace are the ones that failed early.
 *
 * A CLIENT-SUPPLIED ID IS ACCEPTED BUT NEVER TRUSTED VERBATIM. Propagating
 * one lets the Flutter app tie its own crash report to a server log, which is
 * genuinely useful. But the value lands in log lines, so it is length-capped
 * and stripped to a conservative character set first - otherwise a crafted
 * header injects newlines and writes forged entries into the log, which is
 * log injection and is how an attacker hides what they did. Anything that
 * fails the check is replaced rather than rejected: the request is not the
 * customer's fault and should still be traceable.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    /** Long enough not to collide in a log, short enough to read aloud on the phone. */
    private static final int GENERATED_BYTES = 8;
    private static final int MAX_CLIENT_ID_LENGTH = 64;

    /**
     * SecureRandom rather than a counter or a timestamp: an id that encodes
     * when the request arrived, or how many have been served, tells anyone
     * holding it something about the shop's traffic.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String requestId = sanitise(request.getHeader(HEADER));
        if (requestId == null) {
            requestId = generate();
        }

        MDC.put(MDC_KEY, requestId);
        // Set on the response BEFORE the chain runs. If something downstream
        // commits the response early - an error page, a streamed body - a
        // header added afterwards would be silently dropped, and the one
        // request the customer is asking about would be the one with no id.
        response.setHeader(HEADER, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Always cleared. Tomcat reuses threads, so a leaked MDC entry
            // would stamp the NEXT customer's request with this one's id -
            // which is worse than having no id at all, because it is
            // confidently wrong.
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * Keeps a client id only if it is short and made of characters that
     * cannot break a log line. Returns null for anything else, which the
     * caller replaces with a generated id.
     */
    static String sanitise(String candidate) {
        if (candidate == null || candidate.isBlank() || candidate.length() > MAX_CLIENT_ID_LENGTH) {
            return null;
        }
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!allowed) {
                return null;
            }
        }
        return candidate;
    }

    static String generate() {
        byte[] bytes = new byte[GENERATED_BYTES];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
