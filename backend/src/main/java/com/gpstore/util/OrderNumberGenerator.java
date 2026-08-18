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
}
