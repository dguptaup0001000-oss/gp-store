package com.gpstore.support;

import org.springframework.dao.DataIntegrityViolationException;

/**
 * Teardown for any test whose fixtures include a delivery partner.
 *
 * THE RACE THIS EXISTS TO LOSE GRACEFULLY. Fourteen classes in this suite each
 * cache their own Spring context, and Spring never closes them - so a context
 * whose test class finished minutes ago still has a live dispatcher polling the
 * one shared database. While a fixture partner exists with available = true it
 * is a real auto-assignment candidate for that dispatcher, which will happily
 * open a batch against it and queue a notification to its account, mid-teardown,
 * from a thread the test cannot see or await.
 *
 * The result is a foreign key violation on a DELETE that was correct when it
 * was written, blaming a test that did nothing wrong. It has now been diagnosed
 * three separate times, in three files, and patched three different ways:
 *
 *   WorkerDeliveryStatusTest  retired its partners as the LAST statement of
 *                             teardown, so the window stayed open for the whole
 *                             of it.
 *   WorkerPackScanTest        wrapped only the partner deletes in a tolerant
 *                             try/catch, leaving the customer delete three
 *                             lines below it unguarded - so a deliberately
 *                             tolerated leftover became a red suite anyway.
 *   WorkerLoginAccountTest    left its partners available and active, making
 *                             them candidates for every test that ran after.
 *
 * One mechanism, in one place, so the fourth class does not have to rediscover
 * it. The order is the fix and the retry is the safety net:
 *
 *   1. RETIRE FIRST, on every pass. An unavailable, inactive partner is not a
 *      candidate, so no NEW assignment can start. A dispatcher that was already
 *      mid-assignment can still write availability back, which is why this runs
 *      again each time rather than once.
 *   2. DELETE AS ONE UNIT. Every statement, in dependency order, inside the
 *      same attempt - never some-guarded-some-not.
 *   3. RETRY. Work already committed lands within milliseconds, and by the
 *      second pass there is no available partner left to start more.
 */
public final class DispatchSafeTeardown {

    /** Enough for work already in flight; small enough that a real ordering bug still fails. */
    static final int ATTEMPTS = 4;

    private DispatchSafeTeardown() {
    }

    /** What to do when the rows are still referenced after every attempt. */
    public enum WhenStuck {
        /**
         * Fail the test. The right default: a delete that never succeeds is
         * usually a genuine ordering bug, and hiding it moves the failure to
         * whichever unlucky class runs next.
         */
        FAIL,
        /**
         * Leave the rows and carry on. For classes that exercise real dispatch,
         * where an assignment can legitimately still be arriving. The partners
         * are unavailable and inactive by then, so what is left behind is inert
         * - and a handful of leftover rows is a far smaller problem than a red
         * suite that says nothing about the code.
         */
        TOLERATE
    }

    public static void sweep(Runnable retire, Runnable deletes, WhenStuck whenStuck) {
        DataIntegrityViolationException stillReferenced = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            retire.run();
            try {
                deletes.run();
                return;
            } catch (DataIntegrityViolationException raced) {
                // A row that did not exist when this pass started.
                stillReferenced = raced;
            }
        }
        if (whenStuck == WhenStuck.FAIL) {
            throw stillReferenced;
        }
    }
}
