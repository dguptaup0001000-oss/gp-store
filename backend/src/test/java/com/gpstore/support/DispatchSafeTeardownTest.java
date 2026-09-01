package com.gpstore.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The retry mechanism itself, with no database in sight.
 *
 * Pure functions and counters on purpose: the thing being asserted is the
 * ORDER and the NUMBER of attempts, and running this against the real shared
 * database would make it subject to the very race it exists to handle.
 */
@DisplayName("Fixture teardown survives a dispatcher writing underneath it")
class DispatchSafeTeardownTest {

    private static DataIntegrityViolationException fk() {
        return new DataIntegrityViolationException("still referenced");
    }

    @Test
    @DisplayName("a clean sweep runs once")
    void happyPathDoesNotRetry() {
        AtomicInteger retires = new AtomicInteger();
        AtomicInteger deletes = new AtomicInteger();

        DispatchSafeTeardown.sweep(
                retires::incrementAndGet, deletes::incrementAndGet, DispatchSafeTeardown.WhenStuck.FAIL);

        assertEquals(1, deletes.get(), "A teardown that works must not cost four passes.");
        assertEquals(1, retires.get());
    }

    @Test
    @DisplayName("retiring happens before deleting, every pass")
    void retireAlwaysPrecedesDelete() {
        StringBuilder order = new StringBuilder();
        AtomicInteger attempts = new AtomicInteger();

        DispatchSafeTeardown.sweep(
                () -> order.append('R'),
                () -> {
                    order.append('D');
                    // Fail once, then succeed.
                    if (attempts.incrementAndGet() == 1) throw fk();
                },
                DispatchSafeTeardown.WhenStuck.FAIL);

        // THE WHOLE POINT. Retiring after deleting leaves the partner a live
        // auto-assignment candidate for the duration of the deletes, which is
        // exactly the bug this class was extracted to stop repeating.
        assertEquals("RDRD", order.toString());
    }

    @Test
    @DisplayName("a row arriving mid-teardown is swept by the next pass")
    void racedRowIsSweptOnRetry() {
        AtomicInteger attempts = new AtomicInteger();

        assertDoesNotThrow(() -> DispatchSafeTeardown.sweep(
                () -> {},
                () -> {
                    if (attempts.incrementAndGet() < 3) throw fk();
                },
                DispatchSafeTeardown.WhenStuck.FAIL));

        assertEquals(3, attempts.get());
    }

    @Test
    @DisplayName("FAIL rethrows when it never succeeds - a real ordering bug still fails")
    void permanentFailureStillFails() {
        AtomicInteger attempts = new AtomicInteger();

        DataIntegrityViolationException thrown = assertThrows(
                DataIntegrityViolationException.class,
                () -> DispatchSafeTeardown.sweep(
                        () -> {},
                        () -> {
                            attempts.incrementAndGet();
                            throw fk();
                        },
                        DispatchSafeTeardown.WhenStuck.FAIL));

        assertEquals(DispatchSafeTeardown.ATTEMPTS, attempts.get());
        assertNotNull(thrown);
    }

    @Test
    @DisplayName("TOLERATE gives up quietly after the same number of attempts")
    void tolerateSwallowsOnlyAfterTrying() {
        AtomicInteger attempts = new AtomicInteger();

        assertDoesNotThrow(() -> DispatchSafeTeardown.sweep(
                () -> {},
                () -> {
                    attempts.incrementAndGet();
                    throw fk();
                },
                DispatchSafeTeardown.WhenStuck.TOLERATE));

        assertEquals(DispatchSafeTeardown.ATTEMPTS, attempts.get(),
                "Tolerating must not mean giving up on the first pass.");
    }

    @Test
    @DisplayName("anything that is not a foreign key clash propagates immediately")
    void unrelatedFailuresAreNotRetried() {
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> DispatchSafeTeardown.sweep(
                () -> {},
                () -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("a real bug");
                },
                DispatchSafeTeardown.WhenStuck.TOLERATE));

        assertEquals(1, attempts.get(),
                "Retrying a genuine error three more times only hides it slower.");
    }
}
