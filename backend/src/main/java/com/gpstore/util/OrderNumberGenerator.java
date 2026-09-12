package com.gpstore.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Pure formatting only - the actual sequence value MUST come from
 * OrderRepository.nextOrderNumberSequenceValue() (a real Postgres sequence,
 * see the V6 migration), never from in-process state. A JVM-memory counter
 * here previously reset to 1 on every restart/deploy and could then
 * generate an order number that collided with one already used earlier
 * that same day, crashing checkout with an unhandled 500 on the unique
 * constraint - a database sequence survives restarts, so that class of
 * collision is no longer possible.
 */
public class OrderNumberGenerator {

    public static String generate(long sequenceValue) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        return "GP" + date + String.format("%06d", sequenceValue);
    }

    /**
     * The number for a whole checkout, which may be several shops' orders.
     *
     * SAME SEQUENCE, DIFFERENT PREFIX. Sharing the sequence means a group
     * number can never collide with an order number, and a customer reading
     * "GPG20260905000123" out to a support agent cannot be mistaken for an
     * order - which matters most in exactly the conversation where it would:
     * "I want to cancel GP..." when only one shop's half should be cancelled.
     */
    public static String generateGroupNumber(long sequenceValue) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        return "GPG" + date + String.format("%06d", sequenceValue);
    }
}
