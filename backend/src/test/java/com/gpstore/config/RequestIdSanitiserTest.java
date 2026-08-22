package com.gpstore.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The sanitiser and the generator, tested directly.
 *
 * IN com.gpstore.config DELIBERATELY. Both methods are package-private, and
 * the alternative was widening them to public purely so a test in another
 * package could reach them. Visibility should describe what the code offers
 * the rest of the application, not what a test found convenient - so the test
 * moved instead of the API.
 *
 * The MockMvc half of this coverage stays in
 * com.gpstore.security.RequestIdFilterTest, where it exercises the filter
 * through a real request.
 */
class RequestIdSanitiserTest {

    @Test
    @DisplayName("the sanitiser accepts only safe ids")
    void sanitiserRules() {
        assertEquals("abc-123_XYZ", RequestIdFilter.sanitise("abc-123_XYZ"));

        assertNull(RequestIdFilter.sanitise(null));
        assertNull(RequestIdFilter.sanitise(""));
        assertNull(RequestIdFilter.sanitise("   "));
        assertNull(RequestIdFilter.sanitise("has space"));
        assertNull(RequestIdFilter.sanitise("has\nnewline"));
        assertNull(RequestIdFilter.sanitise("has\rreturn"));
        assertNull(RequestIdFilter.sanitise("semi;colon"));
        assertNull(RequestIdFilter.sanitise("%X{injection}"));
        assertNull(RequestIdFilter.sanitise("a".repeat(65)), "an over-long id must be refused");
        assertEquals("a".repeat(64), RequestIdFilter.sanitise("a".repeat(64)), "64 is the boundary and is allowed");
    }

    @Test
    @DisplayName("generated ids are hex, non-empty and not sequential")
    void generatedIdsAreOpaque() {
        String a = RequestIdFilter.generate();
        String b = RequestIdFilter.generate();

        assertEquals(16, a.length(), "8 random bytes render as 16 hex characters");
        assertTrue(a.matches("[0-9a-f]+"));
        assertNotEquals(a, b);
        // A counter or a timestamp would leak traffic volume or arrival time
        // to anyone holding an id; random bytes leak neither.
    }
}
