package com.gpstore.enums;

import java.util.Locale;
import java.util.Optional;

/**
 * The states a delivery can be in, and the only ones it can be in.
 *
 * WHY THIS TYPE EXISTS. Delivery.deliveryStatus is a String column, and
 * DeliveryService.updateDeliveryStatus used to do exactly this:
 *
 *     delivery.setDeliveryStatus(status);
 *
 * with `status` arriving as a @RequestParam straight from a phone. Nothing
 * checked it. "BANANA" was a valid delivery status. So was "", and so was
 * "delivered " with a trailing space - which is worse than nonsense, because
 * it is nonsense that LOOKS right in the admin list while failing every
 * equalsIgnoreCase check the code makes against it.
 *
 * The real damage was the ordering, not the vocabulary: the DELIVERED branch
 * fires on the string alone, so a delivery partner could mark an order
 * delivered while it was still sitting on the packing bench - stamping
 * deliveredAt, telling the customer it had arrived, and completing the COD
 * payment for money nobody had collected. There was no check that the order
 * had ever been picked up, or packed, or even assigned to anybody.
 *
 * THE COLUMN STAYS A STRING on purpose. Making it an enum column would need a
 * migration over live delivery rows written before this type existed, and a
 * Hibernate CHECK constraint that would then have to be widened by hand every
 * time a state is added - a trap this codebase has already been caught by
 * twice. Parsing at the boundary gives the same guarantee where it matters:
 * nothing unrecognised gets in from outside.
 */
public enum DeliveryStatus {

    /** Handed to a partner. What DeliveryService writes when a delivery is created. */
    ASSIGNED,

    /**
     * The shop finished packing and a worker scanned the order's QR code.
     *
     * Deliberately the same word as OrderStatus.PACKED, because it is the same
     * event seen from the delivery's side. The customer is told the order is
     * packed and nothing more - see OrderStatus.PACKED for why that sentence
     * is chosen so carefully.
     */
    PACKED,

    /** The worker physically has the order. Still at the shop; nothing has left. */
    PICKED_UP,

    /** On the road. This is the first state that means anything is moving. */
    OUT_FOR_DELIVERY,

    /** Handed over. Terminal, and the only state that completes a COD payment. */
    DELIVERED,

    /**
     * Nobody was home, the address was wrong, the customer refused it.
     *
     * Not terminal: a failed attempt usually gets tried again, so this can go
     * back OUT_FOR_DELIVERY. It can also end as RETURNED when the goods come
     * back to the shop.
     */
    FAILED,

    /** The goods came back to the shop. Terminal. */
    RETURNED,

    /** Called off before it went out. Terminal. */
    CANCELLED;

    /**
     * Parses one status name, or nothing.
     *
     * Case-insensitive and trimmed, because the alternative is rejecting
     * "delivered" from a client that lower-cased it and calling that a
     * security boundary. An empty Optional is the honest answer for anything
     * that is not one of the constants above - the caller decides what to do
     * about it, and every caller here refuses the request.
     */
    public static Optional<DeliveryStatus> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String cleaned = raw.trim().toUpperCase(Locale.ROOT);
        if (cleaned.isEmpty()) {
            return Optional.empty();
        }
        for (DeliveryStatus value : values()) {
            if (value.name().equals(cleaned)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    /** Nothing moves on from here. */
    public boolean isTerminal() {
        return this == DELIVERED || this == RETURNED || this == CANCELLED;
    }
}
