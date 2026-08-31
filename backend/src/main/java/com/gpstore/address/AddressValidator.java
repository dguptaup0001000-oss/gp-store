package com.gpstore.address;

import com.gpstore.entity.Address;
import com.gpstore.exception.BadRequestException;

import java.util.regex.Pattern;

/**
 * What the server checks before an address is stored.
 *
 * NOTHING HERE TRUSTS THE CLIENT. AddressController binds the request body
 * straight onto the entity, so every field below arrives exactly as some
 * phone chose to send it - and "some phone" includes one with a proxy in
 * front of it. The Flutter form's own validation is a courtesy to the
 * customer; this is the rule.
 *
 * WHY IT IS NOT BEAN VALIDATION ON THE ENTITY. @NotNull on a mapped field
 * changes how Hibernate reads the column when it validates the schema at
 * startup, and this application runs ddl-auto=validate in production. A
 * nullability disagreement there is not a warning - it is a service that will
 * not start. Explicit checks cost a class and are testable without a context.
 */
public final class AddressValidator {

    private AddressValidator() {
    }

    /**
     * Indian PIN: six digits, and the first is never zero.
     *
     * The leading digit is the postal region (1-8; 9 is Army Postal Service),
     * so 0xxxxx is not a PIN anywhere in India and is almost always a typo or
     * a padded number.
     */
    private static final Pattern PIN = Pattern.compile("^[1-9][0-9]{5}$");

    /** Matches the varchar widths V34 and the pre-existing columns declare. */
    private static final int LEN_SHORT = 120;
    private static final int LEN_NAME = 200;
    private static final int LEN_LANDMARK = 300;
    private static final int LEN_INSTRUCTIONS = 500;

    /**
     * Checks an address a customer is trying to save, and normalises the few
     * things that are safe to normalise.
     *
     * Throws on the first real problem rather than collecting them: these are
     * fields a form already filtered, so anything arriving here is either a
     * client bug or someone poking at the API, and neither needs a tidy list.
     */
    public static void validateForSave(Address address) {
        if (address == null) {
            throw new BadRequestException("Address is required.");
        }

        requireText(address.getHouseNo(), "House/flat/shop number", LEN_SHORT);
        requireText(address.getArea(), "Locality", LEN_NAME);
        requireText(address.getCity(), "City", LEN_SHORT);
        requireText(address.getState(), "State", LEN_SHORT);

        // Optional, but still bounded. A 20 KB "landmark" is not a landmark,
        // and the column is 300 characters wide - letting it through means the
        // database rejects the insert with a message no customer can act on.
        limit(address.getBuildingName(), "Building name", LEN_NAME);
        limit(address.getFloor(), "Floor", 50);
        limit(address.getStreet(), "Street", LEN_NAME);
        limit(address.getLandmark(), "Landmark", LEN_LANDMARK);
        limit(address.getDeliveryInstructions(), "Delivery instructions", LEN_INSTRUCTIONS);
        limit(address.getLabel(), "Label", 20);
        limit(address.getDistrict(), "District", LEN_SHORT);
        limit(address.getFullName(), "Name", LEN_NAME);

        validatePincode(address.getPincode());
        validateCoordinates(address.getLatitude(), address.getLongitude());
        validateAccuracy(address.getLocationAccuracy());
    }

    public static void validatePincode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("PIN code is required.");
        }
        // Customers type "273 001" and paste "273001 ". Both are the same PIN.
        String cleaned = raw.replaceAll("\\s+", "");
        if (!PIN.matcher(cleaned).matches()) {
            throw new BadRequestException(
                    "Enter a valid 6-digit Indian PIN code.");
        }
    }

    /** The PIN with spaces removed, for storing. */
    public static String normalisePincode(String raw) {
        return raw == null ? null : raw.replaceAll("\\s+", "");
    }

    /**
     * Coordinates a delivery can actually be sent to.
     *
     * THE RANGES ARE THE EASY HALF. NaN and infinity both pass a naive
     * `lat < -90 || lat > 90` check - every comparison against NaN is false -
     * and would then poison every distance calculation the order touches,
     * because NaN propagates silently through arithmetic instead of throwing.
     *
     * (0, 0) is rejected on its own: it is in the Atlantic off Ghana, and in
     * practice it means a client sent an uninitialised pair of doubles rather
     * than that a customer is at Null Island.
     */
    public static void validateCoordinates(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            throw new BadRequestException(
                    "Confirm the delivery location on the map before saving this address.");
        }
        if (latitude.isNaN() || longitude.isNaN()
                || latitude.isInfinite() || longitude.isInfinite()) {
            throw new BadRequestException("The delivery location is not a valid coordinate.");
        }
        if (latitude < -90.0 || latitude > 90.0) {
            throw new BadRequestException("Latitude must be between -90 and 90.");
        }
        if (longitude < -180.0 || longitude > 180.0) {
            throw new BadRequestException("Longitude must be between -180 and 180.");
        }
        if (latitude == 0.0 && longitude == 0.0) {
            throw new BadRequestException(
                    "The delivery location was not captured. Move the pin to your address and confirm it.");
        }
    }

    /** Metres, and metres cannot be negative or infinite. */
    private static void validateAccuracy(Double accuracyMetres) {
        if (accuracyMetres == null) {
            return;
        }
        if (accuracyMetres.isNaN() || accuracyMetres.isInfinite() || accuracyMetres < 0) {
            throw new BadRequestException("Location accuracy is not a valid measurement.");
        }
    }

    private static void requireText(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(field + " is required.");
        }
        limit(value, field, max);
    }

    private static void limit(String value, String field, int max) {
        if (value != null && value.length() > max) {
            throw new BadRequestException(
                    field + " is too long (maximum " + max + " characters).");
        }
    }
}
