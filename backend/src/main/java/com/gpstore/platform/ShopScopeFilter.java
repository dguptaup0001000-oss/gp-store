package com.gpstore.platform;

/**
 * Names for the Hibernate filter that restricts a query to one shop.
 *
 * WHY A FILTER AND NOT {@code @TenantId}. Hibernate 6 ships @TenantId, which
 * is the obvious tool and the wrong one here: it applies unconditionally,
 * with no way to step outside the tenant. This application has work that
 * legitimately spans shops - the outbox worker draining events for every
 * shop, the stuck-refund sweep, the late-delivery flagger, payment expiry,
 * and Platform Admin itself. Under @TenantId each of those would silently see
 * one shop's rows. Not fail - see LESS, which is the failure mode discovered
 * by a shopkeeper asking why their refund was never chased.
 *
 * A filter can be enabled per session, so platform work simply does not
 * enable it and says so at the call site through TenantScope.platform().
 * The unscoped case becomes something a reader can see rather than something
 * the framework does invisibly.
 *
 * THREE THINGS A FILTER DOES NOT COVER. Two of them are closed elsewhere and
 * one is an open debt; all three are asserted on by ShopScopeIsNotOptionalTest
 * so that a new one cannot arrive unnoticed.
 *
 *   find() BY PRIMARY KEY is not filtered - loading by id goes through the
 *   persistence context rather than a query, which is exactly how an
 *   id-manipulation attack arrives. CLOSED by TenantEntityListener's @PostLoad
 *   check, which refuses any shop-owned row that does not belong to the shop
 *   in scope, however it was reached.
 *
 *   INSERTS are not filtered - a filter rewrites reads and does nothing to a
 *   write, so a correctly-filtered application would still write rows with no
 *   shop. CLOSED by the same listener's @PrePersist stamp.
 *
 *   NATIVE QUERIES AND BULK STATEMENTS are not filtered. @Query(nativeQuery =
 *   true) goes straight to the database, and a bulk "update ... where ..."
 *   reaches every shop's rows. OPEN: each one needs a hand-written shop
 *   predicate, and until then each is listed by name in that test with the
 *   reason it is safe - or, for the daily revenue chart, the reason it is not
 *   yet.
 */
public final class ShopScopeFilter {

    /** Hibernate filter name. */
    public static final String NAME = "shopScope";

    /** The parameter the filter's condition binds. */
    public static final String SHOP_ID_PARAM = "shopId";

    /** The SQL the filter appends. Raw column, so no mapped field is required. */
    public static final String CONDITION = "shop_id = :shopId";

    private ShopScopeFilter() {
    }
}
