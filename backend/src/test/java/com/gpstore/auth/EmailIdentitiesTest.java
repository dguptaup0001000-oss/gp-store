package com.gpstore.auth;

import com.gpstore.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailIdentitiesTest {

    @Test
    void normalizesAndRejectsInvalid() {
        assertEquals("shop@example.com", EmailIdentities.normalize("  Shop@Example.com "));
        assertThrows(BadRequestException.class, () -> EmailIdentities.normalize("not-an-email"));
        assertThrows(BadRequestException.class, () -> EmailIdentities.normalize(""));
    }

    @Test
    void maskHidesTheLocalPart() {
        assertEquals("s***@example.com", EmailIdentities.mask("shop@example.com"));
        assertTrue(!EmailIdentities.mask("shop@example.com").contains("shop@"));
    }
}
