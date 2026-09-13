package com.gpstore.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M-000001 and S-000001 are for reading aloud, not for deciding.
 *
 * §3 says these must not be relied on for authorization, and the surest way to
 * honour that is for them not to exist anywhere a decision is made. These
 * tests pin the shape; the guarantee that nothing parses them is kept by there
 * being no parser to test.
 */
@DisplayName("Public identifiers")
class PublicIdsTest {

    @Test
    @DisplayName("they read the way the specification writes them")
    void theShapeIsTheShapeAsked() {
        assertEquals("M-000001", PublicIds.merchant(1L));
        assertEquals("S-000001", PublicIds.shop(1L));
        assertEquals("M-000004", PublicIds.merchant(4L));
        assertEquals("S-000123", PublicIds.shop(123L));
    }

    @Test
    @DisplayName("the millionth shop is not a collision with the first")
    void paddingWidensRatherThanTruncates() {
        // A pad that truncated to six digits would give the 1,000,000th shop
        // the same reference as the 0th, and a reference that two rows share
        // is worse than no reference at all - somebody reads it out over the
        // phone and the wrong shop gets suspended.
        assertEquals("S-1000000", PublicIds.shop(1_000_000L));
        assertEquals("M-9999999", PublicIds.merchant(9_999_999L));
        assertTrue(PublicIds.shop(1_000_000L).length() > PublicIds.shop(1L).length());
    }

    @Test
    @DisplayName("no id, no reference")
    void nullIsAnAnswer() {
        // A shop with no merchant is a real state during onboarding. "M-null"
        // on a screen is worse than an empty space.
        assertNull(PublicIds.merchant(null));
        assertNull(PublicIds.shop(null));
    }

    @Test
    @DisplayName("a merchant reference and a shop reference are never confusable")
    void theyAreToldApartAtAGlance() {
        // §3 wants merchant and shop ids clearly distinguishable. Reading
        // "000001" without its prefix is the mistake this prevents.
        for (long id = 1; id < 50; id++) {
            assertTrue(PublicIds.merchant(id).startsWith("M-"));
            assertTrue(PublicIds.shop(id).startsWith("S-"));
            assertTrue(!PublicIds.merchant(id).equals(PublicIds.shop(id)));
        }
    }
}
