package com.gpstore.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Puts a test account on a shop's staff list, because a role is not a posting.
 *
 * <p>WHY SO MANY FIXTURES NEEDED THIS AT ONCE. Test after test created a
 * customer, ran {@code UPDATE customers SET role = 'MANAGER'}, and started
 * making merchant requests. That worked for one reason only: the platform mode
 * defaulted to SINGLE_SHOP, where {@code TenantResolver} answered every
 * credential with Shop #1 - so an account nobody had hired still resolved into
 * the shop and could act in it.
 *
 * <p>That default was the bug. On a marketplace an account on nobody's staff
 * list has no shop, and picking one for them would invent an authorization
 * nobody granted - which is exactly what {@code MarketplaceIdentityTest}
 * asserts. When the default became the marketplace, every fixture that had been
 * leaning on the fallback started getting refused, and each was refused
 * correctly.
 *
 * <p>So this is not a workaround for the change. It is the fixtures finally
 * describing what they always meant: a member of staff, at a shop. Tests whose
 * subject IS the unposted account - can somebody nobody hired act as a merchant
 * - must not call this, and do not.
 */
public final class StaffPosting {

    private StaffPosting() {
    }

    /**
     * Posts the account with this email to the deployment's first shop.
     *
     * <p>Idempotent, so a fixture that is re-entered does not trip the
     * uniqueness of (shop_id, customer_id).
     */
    public static void postToFirstShop(JdbcTemplate jdbc, String email) {
        postTo(jdbc, firstShopId(jdbc), email);
    }

    /** Posts the account with this email to a named shop. */
    public static void postTo(JdbcTemplate jdbc, Long shopId, String email) {
        jdbc.update("""
                INSERT INTO shop_staff (shop_id, customer_id, is_default, active)
                SELECT ?, c.id, true, true FROM customers c
                WHERE c.email = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM shop_staff s
                      WHERE s.shop_id = ? AND s.customer_id = c.id)
                """, shopId, email, shopId);
    }

    /** The shop this deployment started with - Shop #1 unless a test says otherwise. */
    public static Long firstShopId(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "SELECT id FROM shops WHERE deleted_at IS NULL ORDER BY id ASC LIMIT 1",
                Long.class);
    }
}
