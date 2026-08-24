package com.gpstore.auth;

import com.gpstore.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IndianPhoneNumbersTest {

    @Test
    void tenDigitNumberGetsCountryCode() {
        assertEquals("919876543210", IndianPhoneNumbers.normalizeTo91("9876543210"));
        assertEquals("9876543210", IndianPhoneNumbers.toLocal10("9876543210"));
    }

    @Test
    void plusNinetyOneIsNotDoubled() {
        assertEquals("919876543210", IndianPhoneNumbers.normalizeTo91("+919876543210"));
        assertEquals("919876543210", IndianPhoneNumbers.normalizeTo91("919876543210"));
    }

    @Test
    void leadingZeroLocalNumber() {
        assertEquals("919876543210", IndianPhoneNumbers.normalizeTo91("09876543210"));
    }

    @Test
    void decorationIsStripped() {
        assertEquals("919876543210", IndianPhoneNumbers.normalizeTo91("+91 98765 43210"));
    }

    @Test
    void doubleCountryCodeIsRejected() {
        assertThrows(BadRequestException.class, () -> IndianPhoneNumbers.normalizeTo91("91919876543210"));
    }

    @Test
    void landlineAndShortNumbersAreRejected() {
        assertThrows(BadRequestException.class, () -> IndianPhoneNumbers.normalizeTo91("12345"));
        assertThrows(BadRequestException.class, () -> IndianPhoneNumbers.normalizeTo91("5123456789"));
        assertThrows(BadRequestException.class, () -> IndianPhoneNumbers.normalizeTo91("abcdefghij"));
    }

    @Test
    void maskKeepsLastFourOnly() {
        assertEquals("******3210", IndianPhoneNumbers.mask("+91 9876543210"));
        assertEquals("******", IndianPhoneNumbers.mask("nope"));
    }
}
