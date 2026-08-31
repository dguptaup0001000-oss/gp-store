package com.gpstore.address;

import com.gpstore.entity.Address;
import com.gpstore.exception.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the server refuses to store as a delivery location.
 *
 * These are the checks that stand between a request body and a coordinate the
 * shop will later send a rider to, so every one of them is written from the
 * attacker's side rather than the form's: the question is not "does the app
 * send this" but "what happens when something else does".
 */
class AddressValidatorTest {

    private static Address valid() {
        Address a = new Address();
        a.setHouseNo("42");
        a.setArea("Gupta Nagar");
        a.setCity("Gorakhpur");
        a.setState("Uttar Pradesh");
        a.setPincode("273001");
        a.setLatitude(26.7606);
        a.setLongitude(83.3732);
        return a;
    }

    @Test
    @DisplayName("a complete Gorakhpur address is accepted")
    void acceptsAGoodAddress() {
        assertDoesNotThrow(() -> AddressValidator.validateForSave(valid()));
    }

    @Nested
    @DisplayName("coordinates")
    class Coordinates {

        @Test
        @DisplayName("NaN is rejected, and this is the case a range check alone misses")
        void rejectsNaN() {
            // Every comparison against NaN is false, so `lat < -90 || lat > 90`
            // lets it straight through - and NaN then propagates silently
            // through every distance calculation the order touches instead of
            // throwing anywhere near the cause.
            assertThrows(BadRequestException.class,
                    () -> AddressValidator.validateCoordinates(Double.NaN, 83.37));
            assertThrows(BadRequestException.class,
                    () -> AddressValidator.validateCoordinates(26.76, Double.NaN));
        }

        @Test
        @DisplayName("infinity is rejected for the same reason")
        void rejectsInfinity() {
            assertThrows(BadRequestException.class,
                    () -> AddressValidator.validateCoordinates(Double.POSITIVE_INFINITY, 83.37));
            assertThrows(BadRequestException.class,
                    () -> AddressValidator.validateCoordinates(26.76, Double.NEGATIVE_INFINITY));
        }

        @ParameterizedTest
        @ValueSource(doubles = {90.0001, -90.0001, 91, -91, 999, -999})
        @DisplayName("latitude outside -90..90 is rejected")
        void rejectsOutOfRangeLatitude(double latitude) {
            assertThrows(BadRequestException.class,
                    () -> AddressValidator.validateCoordinates(latitude, 83.37));
        }

        @ParameterizedTest
        @ValueSource(doubles = {180.0001, -180.0001, 181, -181, 99999})
        @DisplayName("longitude outside -180..180 is rejected")
        void rejectsOutOfRangeLongitude(double longitude) {
            assertThrows(BadRequestException.class,
                    () -> AddressValidator.validateCoordinates(26.76, longitude));
        }

        @Test
        @DisplayName("the exact poles and antimeridian are allowed - they are real coordinates")
        void allowsBoundaries() {
            assertDoesNotThrow(() -> AddressValidator.validateCoordinates(90.0, 180.0));
            assertDoesNotThrow(() -> AddressValidator.validateCoordinates(-90.0, -180.0));
        }

        @Test
        @DisplayName("(0,0) is refused as an uninitialised pair, not honoured as Null Island")
        void rejectsNullIsland() {
            assertThrows(BadRequestException.class,
                    () -> AddressValidator.validateCoordinates(0.0, 0.0));
            // But a real coordinate that merely has one zero component is fine.
            assertDoesNotThrow(() -> AddressValidator.validateCoordinates(0.0, 83.37));
        }

        @Test
        @DisplayName("missing coordinates ask for the map step rather than storing a blank")
        void requiresCoordinates() {
            assertThrows(BadRequestException.class,
                    () -> AddressValidator.validateCoordinates(null, 83.37));
            assertThrows(BadRequestException.class,
                    () -> AddressValidator.validateCoordinates(26.76, null));
        }
    }

    @Nested
    @DisplayName("PIN code")
    class Pin {

        @ParameterizedTest
        @ValueSource(strings = {"273001", "110001", "800001", "273 001", " 273001 "})
        @DisplayName("valid Indian PINs are accepted, spaces and all")
        void acceptsValid(String pin) {
            assertDoesNotThrow(() -> AddressValidator.validatePincode(pin));
        }

        @ParameterizedTest
        @ValueSource(strings = {"27300", "2730011", "abcdef", "273-01", "073001", "000000", ""})
        @DisplayName("short, long, non-numeric and zero-leading values are refused")
        void rejectsInvalid(String pin) {
            assertThrows(BadRequestException.class,
                    () -> AddressValidator.validatePincode(pin));
        }

        @Test
        @DisplayName("a null PIN is refused rather than stored")
        void rejectsNull() {
            assertThrows(BadRequestException.class, () -> AddressValidator.validatePincode(null));
        }

        @Test
        @DisplayName("spaces are stripped before storing, so 273 001 and 273001 are one PIN")
        void normalises() {
            assertEquals("273001", AddressValidator.normalisePincode("273 001"));
            assertEquals("273001", AddressValidator.normalisePincode(" 273001 "));
            assertNull(AddressValidator.normalisePincode(null));
        }
    }

    @Nested
    @DisplayName("free text")
    class FreeText {

        @Test
        @DisplayName("an oversized landmark is refused here, not by the database")
        void boundsLandmark() {
            // The column is 300 wide. Without this the insert fails inside
            // Postgres and the customer gets a message about a constraint.
            Address a = valid();
            a.setLandmark("x".repeat(301));
            BadRequestException e = assertThrows(BadRequestException.class,
                    () -> AddressValidator.validateForSave(a));
            assertTrue(e.getMessage().contains("Landmark"), e.getMessage());
        }

        @Test
        @DisplayName("an oversized delivery instruction is refused")
        void boundsInstructions() {
            Address a = valid();
            a.setDeliveryInstructions("y".repeat(501));
            assertThrows(BadRequestException.class, () -> AddressValidator.validateForSave(a));
        }

        @Test
        @DisplayName("text at exactly the limit is allowed - the bound is not off by one")
        void allowsExactlyTheLimit() {
            Address a = valid();
            a.setLandmark("x".repeat(300));
            a.setDeliveryInstructions("y".repeat(500));
            assertDoesNotThrow(() -> AddressValidator.validateForSave(a));
        }

        @Test
        @DisplayName("required fields are named individually so a customer can fix one")
        void namesTheMissingField() {
            Address a = valid();
            a.setCity("   ");
            BadRequestException e = assertThrows(BadRequestException.class,
                    () -> AddressValidator.validateForSave(a));
            assertTrue(e.getMessage().contains("City"), e.getMessage());
        }

        @Test
        @DisplayName("markup in an instruction is stored as text, not rejected as an attack")
        void keepsOddButHarmlessText() {
            // Deliberately NOT filtered here. The protection is that this value is
            // rendered as a Flutter Text widget and bound as a JDBC parameter -
            // never concatenated into SQL and never parsed as markup. Rejecting
            // it would block a customer whose landmark genuinely contains an
            // ampersand or a quote, which is a real Indian address problem.
            Address a = valid();
            a.setDeliveryInstructions("Ring the bell & ask for \"Deepak\" <side lane>");
            assertDoesNotThrow(() -> AddressValidator.validateForSave(a));
        }
    }

    @Test
    @DisplayName("negative or non-finite GPS accuracy is refused")
    void rejectsImpossibleAccuracy() {
        Address a = valid();
        a.setLocationAccuracy(-1.0);
        assertThrows(BadRequestException.class, () -> AddressValidator.validateForSave(a));

        a.setLocationAccuracy(Double.NaN);
        assertThrows(BadRequestException.class, () -> AddressValidator.validateForSave(a));

        a.setLocationAccuracy(8.0);
        assertDoesNotThrow(() -> AddressValidator.validateForSave(a));

        // Null is legitimate: a pin placed by search or dragged by hand has no
        // device accuracy, and inventing one would make it look GPS-verified.
        a.setLocationAccuracy(null);
        assertDoesNotThrow(() -> AddressValidator.validateForSave(a));
    }
}
