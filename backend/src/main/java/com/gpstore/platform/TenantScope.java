package com.gpstore.platform;

import java.util.Objects;

/**
 * Whose data the current unit of work may touch.
 *
 * TWO KINDS, AND THE DIFFERENCE IS THE WHOLE POINT.
 *
 *   ofShop(id)  a request acting for one shop. Every shop-owned query is
 *               restricted to it, and reaching outside is a bug.
 *
 *   platform()  work that legitimately spans shops: the outbox worker
 *               draining events for every shop, the stuck-refund sweep, the
 *               late-delivery flagger, a platform admin looking at the
 *               marketplace. These are not "no tenant" - they are a WIDER
 *               tenant, and calling them that keeps the unscoped case
 *               something a reader has to ask for by name.
 *
 * THERE IS NO THIRD KIND. In particular there is no "unknown" or "not set"
 * that silently means everything: an absent scope is an error, because the
 * failure mode of guessing is one shop reading another's orders.
 */
public final class TenantScope {

    private static final TenantScope PLATFORM = new TenantScope(null);

    private final Long shopId;

    private TenantScope(Long shopId) {
        this.shopId = shopId;
    }

    /** A request acting for exactly one shop. */
    public static TenantScope ofShop(Long shopId) {
        if (shopId == null) {
            throw new IllegalArgumentException(
                    "A shop scope needs a shop. Use TenantScope.platform() to act across shops "
                            + "deliberately, so that reading everything is never something that "
                            + "happens by omission.");
        }
        return new TenantScope(shopId);
    }

    /**
     * Work that spans every shop.
     *
     * Deliberately verbose at the call site. A background job written as
     * TenantScope.platform() says what it is doing; the same job with no
     * scope at all would say nothing, and would read identically to a
     * request that had simply forgotten to set one.
     */
    public static TenantScope platform() {
        return PLATFORM;
    }

    public boolean isPlatform() {
        return shopId == null;
    }

    public boolean isSingleShop() {
        return shopId != null;
    }

    /** The shop, or null when this scope spans all of them. */
    public Long shopId() {
        return shopId;
    }

    /** The shop, or a failure - for code that genuinely cannot proceed without one. */
    public Long requireShopId() {
        if (shopId == null) {
            throw new IllegalStateException(
                    "This operation belongs to one shop, but the current scope spans the whole "
                            + "platform. A platform-wide caller must name the shop it is acting for.");
        }
        return shopId;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TenantScope s && Objects.equals(shopId, s.shopId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(shopId);
    }

    @Override
    public String toString() {
        return isPlatform() ? "TenantScope[platform]" : "TenantScope[shop=" + shopId + "]";
    }
}
