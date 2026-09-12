package com.gpstore.platform;

import java.util.concurrent.Callable;

/**
 * The scope the current thread is working in.
 *
 * A THREAD LOCAL, AND IT HAS TO BE CLEARED. Tomcat reuses threads across
 * requests, so a scope left behind by request A is the scope request B starts
 * with - which is precisely a cross-shop data leak, arriving by accident and
 * looking like nothing. Every entry point that opens a scope closes it in a
 * finally block, and {@link #runWithin} exists so callers do not have to
 * remember.
 *
 * NOT INHERITABLE ON PURPOSE. An InheritableThreadLocal would hand the
 * current shop to every thread the request happens to spawn, including pool
 * threads that outlive it and then serve somebody else. Async work states its
 * own scope.
 *
 * READING IT WHEN NOTHING SET IT IS AN ERROR, not a default. See
 * {@link #require()}.
 */
public final class TenantContext {

    private static final ThreadLocal<TenantScope> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    /** The current scope, or null when none has been established. */
    public static TenantScope current() {
        return CURRENT.get();
    }

    /**
     * The current scope, or a failure.
     *
     * FAILING IS THE SAFE ANSWER. The tempting alternative - default to the
     * platform scope when nothing is set - turns every forgotten scope into a
     * query that reads every shop's data and returns a 200. A loud failure in
     * a test is cheap; a quiet one in production is a breach.
     */
    public static TenantScope require() {
        TenantScope scope = CURRENT.get();
        if (scope == null) {
            throw new IllegalStateException(
                    "No tenant scope on this thread. Every unit of work must say whose data it "
                            + "may touch - a request through TenantContextFilter, or background "
                            + "work through TenantContext.runWithin(TenantScope.platform(), ...).");
        }
        return scope;
    }

    /**
     * The shop a report should be about, or null for every shop.
     *
     * THE ONE PLACE A QUERY PARAMETER IS ALLOWED TO COME FROM. Four
     * reporting queries reach a shop-owned entity through a join, and
     * Hibernate's filter does not follow a join - it restricts the entity a
     * query is ROOTED on and nothing else. Those queries therefore carry an
     * explicit shopId, and this is where it comes from: the scope on the
     * thread, which TenantContextFilter derived from the credential. Never a
     * request parameter (§78), and never a field on an object a client sent.
     *
     * NULL IS AN ANSWER, NOT AN OMISSION. A platform administrator looking at
     * the marketplace, and the scheduled reporting a single-shop deployment
     * has always run, are both legitimately about every shop. The queries
     * spell that as "(:shopId is null or ...)" so the widening is visible in
     * the SQL rather than implied by a missing clause.
     *
     * REQUIRES A SCOPE. Returning null when nothing is set would make a
     * forgotten scope look exactly like a deliberate platform report, which
     * is the failure this whole mechanism exists to prevent.
     */
    public static Long reportingShopId() {
        return require().shopId();
    }

    /** Whether a scope has been established at all. */
    public static boolean isSet() {
        return CURRENT.get() != null;
    }

    public static void set(TenantScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("Use clear() to remove the scope, never set(null).");
        }
        CURRENT.set(scope);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Runs something in a scope and puts back whatever was there before.
     *
     * RESTORES RATHER THAN CLEARS, so a nested call cannot strip the scope
     * from the work that called it - a platform sweep that dips into one
     * shop's data and then continues must come back out to the platform
     * scope, not to nothing.
     */
    public static <T> T runWithin(TenantScope scope, Callable<T> work) {
        TenantScope previous = CURRENT.get();
        CURRENT.set(scope);
        try {
            return work.call();
        } catch (RuntimeException | Error unchecked) {
            throw unchecked;
        } catch (Exception checked) {
            throw new IllegalStateException(checked);
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void runWithin(TenantScope scope, Runnable work) {
        runWithin(scope, () -> {
            work.run();
            return null;
        });
    }
}
