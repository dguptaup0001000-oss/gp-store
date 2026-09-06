package com.gpstore.platform;

import org.springframework.core.task.TaskDecorator;

/**
 * Every background thread says whose data it may touch, before it touches any.
 *
 * WHY THIS EXISTS, and it is the same bug twice. A scheduled job and a
 * post-commit callback both run on a pool thread, and a tenant scope is a
 * ThreadLocal - so neither inherits one. Nothing complained, because an absent
 * scope behaves almost exactly like the platform scope: TenantFilterActivator
 * disables the filter, and TenantEntityListener's @PostLoad checks nothing.
 * Reads therefore span every shop, which for a sweep is right.
 *
 * ALMOST EXACTLY. The one place the two differ is an INSERT of a shop-owned
 * row, and that difference is total: with no scope and more than one shop,
 * TenantDefaults.shopIdForNewRow has no answer and throws. That is what took
 * the outbox worker down in MULTI_SHOP_PRODUCTION - every order in the
 * marketplace got no invoice and no rider, and the only sign was a
 * dead-lettered event. See V53.
 *
 * So the difference between "deliberately spans every shop" and "nobody set a
 * scope" was invisible right up until it was catastrophic. TenantScope.platform()
 * has always existed to make that distinction sayable; this makes it said, on
 * every background thread, without anyone having to remember.
 *
 * TWO KINDS OF BACKGROUND WORK, AND THEY GET DIFFERENT ANSWERS:
 *
 *   A SCHEDULED SWEEP belongs to no shop. It exists to visit all of them - the
 *   outbox drain, the stale-payment expiry, the late-delivery flagger - so it
 *   is given the platform scope, by name, once, in the one place every
 *   scheduled task passes through.
 *
 *   A POST-COMMIT CONTINUATION belongs to the request that queued it. Sending
 *   the notification for an order is part of placing that order, and it is
 *   that order's shop. So the scope is CAPTURED at submit time and reinstated
 *   on the worker thread rather than widened to the platform - widening it
 *   would let a continuation write into a shop the request had no business in.
 */
public final class BackgroundWorkScope {

    private BackgroundWorkScope() {
    }

    /**
     * Decorates a scheduled task so it runs platform-wide, deliberately.
     *
     * NESTING IS FINE AND IS USED. OutboxWorker.drain enters each event's own
     * shop scope inside this one; TenantContext restores the outer scope when
     * the inner block ends, which CrossTenantDataIsolationTest already pins.
     *
     * An existing scope is left alone. Nothing installs one before a scheduled
     * task today, but a task invoked directly by a test that has already said
     * which shop it means should get the shop it asked for.
     */
    public static TaskDecorator platformWide() {
        return task -> () -> {
            if (TenantContext.isSet()) {
                task.run();
                return;
            }
            TenantContext.runWithin(TenantScope.platform(), task);
        };
    }

    /**
     * Carries the submitting thread's scope onto the worker thread.
     *
     * FOR WORK THAT IS A CONTINUATION OF A REQUEST, not a sweep. The caller
     * captures at submit time - on the request thread, where the scope is
     * still set - and this reinstates it around the work.
     *
     * A null capture means the submitter had none either. That happens when
     * background work queues more background work, and the honest answer is
     * the platform scope: the same one the outer sweep is already running in.
     */
    public static Runnable carrying(TenantScope captured, Runnable work) {
        TenantScope scope = captured == null ? TenantScope.platform() : captured;
        return () -> TenantContext.runWithin(scope, work);
    }
}
