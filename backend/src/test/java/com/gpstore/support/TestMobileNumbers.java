package com.gpstore.support;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A MOBILE NUMBER NO OTHER FIXTURE WILL PICK.
 *
 * <p>WHAT WAS THERE BEFORE, AND WHY IT WAS WORSE THAN IT LOOKED. Twenty-three
 * test classes built one like this:
 *
 * <pre>{@code "9" + String.valueOf(System.nanoTime()).substring(0, 9)}</pre>
 *
 * <p>which reads as "nine plus nanosecond digits" and is not: taking the
 * first nine characters takes the HIGH-order digits of the nanosecond clock,
 * the ones that move slowest. On this machine they change roughly once every hundred
 * milliseconds, so any two customers created inside the same tenth of a
 * second get the SAME number, and {@code customers.mobile_number} is unique.
 * It was written down as a flake to fix one day; it is a near-certainty
 * whenever two fixtures land close together, and it failed a full build in
 * this pass.
 *
 * <p>THIS CANNOT COLLIDE WITHIN A JVM, because a counter cannot repeat, and is
 * very unlikely to collide across concurrently forked JVMs, because each one
 * draws a different random block to count inside. Both halves matter:
 * Surefire forks, and the forks share one database.
 *
 * <p>TEN DIGITS STARTING WITH A NINE, because that is what the application
 * validates and what a real Indian mobile number looks like. A helper that
 * produced something the validator rejects would move the failure rather than
 * remove it.
 */
public final class TestMobileNumbers {

    private TestMobileNumbers() {
    }

    /**
     * The block this JVM counts inside.
     *
     * <p>Drawn once, at random, from the nine-digit space after the leading 9.
     * A second forked JVM draws its own, so two forks would have to pick the
     * same block AND reach the same offset to collide.
     */
    private static final long BLOCK = ThreadLocalRandom.current().nextLong(0, 900_000_000L);

    private static final AtomicLong NEXT = new AtomicLong();

    /** A number no other caller in this JVM has had or will have. */
    public static String unique() {
        long offset = NEXT.getAndIncrement();
        long digits = (BLOCK + offset) % 900_000_000L;
        return String.format("9%09d", digits);
    }
}
