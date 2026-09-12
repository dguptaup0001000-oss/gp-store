package com.gpstore.platform;

import java.util.function.Supplier;

/**
 * Which shop a row belongs to when nothing on the thread says.
 *
 * WHY THIS IS STATIC, AND WHY IT HOLDS VALUES RATHER THAN BEANS. JPA entity
 * listeners are built by the persistence provider, not by Spring, so they
 * cannot be injected. The smallest thing that closes that gap is two plain
 * values - the deployment's mode, and Shop #1's id - installed once at
 * startup by {@link ShopBootstrap}. Holding a bean here instead would pin a
 * whole application context in a static field, which in a test run with
 * several cached contexts means an entity listener reaching into a context
 * that has since been closed.
 *
 * THE VALUES ARE DEPLOYMENT FACTS, not request state. Nothing a caller sends
 * can change them, and they are read - never written - on the request path.
 */
public final class TenantDefaults {

    private static volatile PlatformMode mode = PlatformMode.SINGLE_SHOP;
    private static volatile Supplier<Long> singleShopIdSource;
    private static volatile Long resolvedSingleShopId;

    private TenantDefaults() {
    }

    /**
     * Installed by {@link TenantDefaultsInstaller} while the context is being
     * built.
     *
     * A SUPPLIER RATHER THAN AN ID, because Shop #1 may not exist yet at the
     * moment this is wired: on a cold start against an empty database the row
     * is created later, by the migration or by ShopBootstrap. Resolving on
     * first use, and caching the answer, means neither ordering can produce a
     * row with no shop.
     */
    public static void install(PlatformMode installedMode, Supplier<Long> firstShopId) {
        mode = installedMode == null ? PlatformMode.SINGLE_SHOP : installedMode;
        singleShopIdSource = firstShopId;
        resolvedSingleShopId = null;
    }

    /** Which mode the installed defaults describe. For tests that change it and put it back. */
    public static PlatformMode installedMode() {
        return mode;
    }

    /** Test seam: put the holder back to the state a fresh JVM starts in. */
    public static void reset() {
        mode = PlatformMode.SINGLE_SHOP;
        singleShopIdSource = null;
        resolvedSingleShopId = null;
    }

    /** Test seam: forget a cached id after a test has rewritten the shops table. */
    public static void forgetResolvedShop() {
        resolvedSingleShopId = null;
    }

    private static Long singleShopId() {
        Long cached = resolvedSingleShopId;
        if (cached != null) {
            return cached;
        }
        Supplier<Long> source = singleShopIdSource;
        if (source == null) {
            return null;
        }
        Long resolved = source.get();
        resolvedSingleShopId = resolved;
        return resolved;
    }

    /**
     * The shop id a row being inserted must carry.
     *
     * THE ACTIVE SCOPE WINS OUTRIGHT, overwriting whatever shop_id the object
     * arrived with. That is the whole defence against a request body, a DTO
     * mapper or a copy-constructor smuggling somebody else's shop into an
     * insert: even if a shop_id reaches the entity, the row is written into
     * the shop the credential resolved to and nowhere else.
     *
     * WITH NO SCOPE, only one of the three answers is a guess and it is not
     * taken:
     *
     *   an explicit value survives - platform-scoped code (the marketplace
     *   admin, a background job that read the shop off the order it is
     *   working on) states the shop deliberately, which is an authorization
     *   the caller already holds;
     *
     *   under SINGLE_SHOP the answer is Shop #1 - not a guess but the only
     *   shop there is, which is what keeps every scheduled job, every webhook
     *   and every existing test writing rows exactly as they do today;
     *
     *   under either multi-shop mode it throws. A marketplace that cannot say
     *   whose order this is must not write one.
     *
     * @param declared   whatever the entity already carried
     * @param entityType only for the message when there is no answer
     */
    /**
     * The shop the work on this thread belongs to.
     *
     * Same rule as {@link #shopIdForNewRow}, for code that needs to READ one
     * shop's row rather than write one - the per-shop settings tables, which
     * are found by shop rather than by a filtered query because a load by
     * primary key is not filtered.
     */
    public static Long shopIdForCurrentWork(Class<?> forWhat) {
        return shopIdForNewRow(null, forWhat);
    }

    public static Long shopIdForNewRow(Long declared, Class<?> entityType) {
        TenantScope scope = TenantContext.current();
        if (scope != null && scope.isSingleShop()) {
            return scope.requireShopId();
        }
        if (declared != null) {
            return declared;
        }
        if (!mode.isMultiShop()) {
            Long only = singleShopId();
            if (only != null) {
                return only;
            }
        }
        throw new IllegalStateException(
                "Refusing to insert a " + entityType.getSimpleName() + " with no shop. Nothing said "
                        + "which shop this row belongs to - no tenant scope on this thread, no shop "
                        + "on the row"
                        + (mode.isMultiShop()
                                ? ", and in " + mode + " there is no single shop to fall back to."
                                : ", and Shop #1 has not been resolved yet."));
    }
}
