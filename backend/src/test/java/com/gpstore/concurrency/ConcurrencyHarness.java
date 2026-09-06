package com.gpstore.concurrency;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs work on several threads that genuinely collide.
 *
 * WHY NOT JUST START THREADS. Two threads started in a loop usually run one
 * after the other: the first has finished its transaction before the second
 * has finished starting. A test written that way passes whether or not the
 * code is safe, which is worse than no test - it is a green tick over an
 * untested race.
 *
 * So every thread here parks on a barrier and is released together, and the
 * ASSERTION IS ON THE INVARIANT rather than on which thread won. Who wins a
 * race is not a property of the system; "stock never goes negative" and
 * "exactly one order exists" are.
 *
 * A TENANT SCOPE IS A ThreadLocal, so each task carries its own - see
 * runInScope. A worker thread that inherited nothing would read across every
 * shop and write into none, which is a different bug from the one under test.
 */
final class ConcurrencyHarness {

    private ConcurrencyHarness() {
    }

    /** What one racing task did: its value, or the exception it threw. */
    record Outcome<T>(T value, Throwable error) {
        boolean succeeded() {
            return error == null;
        }

        boolean failedWith(Class<? extends Throwable> type) {
            return error != null && findCause(error, type) != null;
        }
    }

    static Throwable findCause(Throwable from, Class<? extends Throwable> type) {
        for (Throwable t = from; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (type.isInstance(t)) {
                return t;
            }
        }
        return null;
    }

    /**
     * Runs every task at the same instant and returns what each one did.
     *
     * The barrier is the whole point: each thread does its setup, then blocks
     * until the last one arrives, so the contended operation is entered
     * together rather than in sequence.
     */
    static <T> List<Outcome<T>> raceAll(List<Callable<T>> tasks) {
        int count = tasks.size();
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CyclicBarrier startTogether = new CyclicBarrier(count);
        CountDownLatch finished = new CountDownLatch(count);
        List<AtomicReference<Outcome<T>>> results = new ArrayList<>();

        try {
            for (int i = 0; i < count; i++) {
                AtomicReference<Outcome<T>> slot = new AtomicReference<>();
                results.add(slot);
                Callable<T> task = tasks.get(i);
                pool.submit(() -> {
                    try {
                        startTogether.await(20, TimeUnit.SECONDS);
                        slot.set(new Outcome<>(task.call(), null));
                    } catch (Throwable failure) {
                        slot.set(new Outcome<>(null, failure));
                    } finally {
                        finished.countDown();
                    }
                });
            }

            try {
                assertTrue(finished.await(60, TimeUnit.SECONDS),
                        "a racing task never finished - a deadlock, or a lock nobody released");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for the race to finish");
            }
        } finally {
            pool.shutdownNow();
        }

        List<Outcome<T>> outcomes = new ArrayList<>(count);
        for (AtomicReference<Outcome<T>> slot : results) {
            outcomes.add(slot.get());
        }
        return outcomes;
    }

    static <T> long succeeded(List<Outcome<T>> outcomes) {
        return outcomes.stream().filter(Outcome::succeeded).count();
    }

    static <T> String describe(List<Outcome<T>> outcomes) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < outcomes.size(); i++) {
            Outcome<T> outcome = outcomes.get(i);
            text.append("\n  [").append(i).append("] ");
            if (outcome == null) {
                text.append("never ran");
            } else if (outcome.succeeded()) {
                text.append("ok: ").append(outcome.value());
            } else {
                text.append("threw ").append(outcome.error().getClass().getSimpleName())
                        .append(": ").append(outcome.error().getMessage());
            }
        }
        return text.toString();
    }
}
