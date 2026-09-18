package com.gpstore.platform;

import com.gpstore.repository.OrderRepository;
import org.springframework.stereotype.Component;

/**
 * Answers "is this person a customer of the shop that is asking?".
 *
 * <p>WHY THIS IS ONE OBJECT AND NOT SIX COPIES. The endpoint-by-endpoint sweep
 * of the merchant-reachable surface found the same defect on five different
 * routes: a customer id, an email or a phone number taken out of a URL, looked
 * up with {@code findById}, and answered to whoever held CUSTOMERS_VIEW - which
 * is every shop owner on GP-STORE, because {@code Role.ADMIN} carries it.
 * Customer is deliberately NOT a {@code ShopOwned} entity (a shopper belongs to
 * the platform, not to a shop), so nothing narrowed any of them. Each route was
 * one line away from being right and none of them had that line.
 *
 * <p>A SHOP'S CUSTOMER IS SOMEBODY WHO BOUGHT FROM IT. That is the definition
 * already used by the shop's customer list and by its announcements, and it
 * needs no new column or table: Order is shop-owned, so "has this person
 * ordered from me" is a question the tenant filter already answers. A shop that
 * has served nobody knows nobody, which is the state every shop starts in.
 *
 * <p>REFUSAL IS A 404, NOT A 403. {@code CrossShopAccessException} is mapped to
 * "Not found" by GlobalExceptionHandler on purpose - a 403 would confirm that
 * the account exists, which is exactly what an enumeration of the platform's
 * phone numbers needs to learn. See that handler for the full reasoning.
 */
@Component
public class ShopCustomers {

    private final OrderRepository orders;

    public ShopCustomers(OrderRepository orders) {
        this.orders = orders;
    }

    /**
     * Whether the caller is the platform rather than one shop.
     *
     * <p>Running the marketplace means being able to look up any account on it:
     * support answering a call, a payment being traced, an account being
     * suspended. A shop scope is a shopkeeper, however many permissions the
     * role they hold happens to carry.
     */
    public boolean readsEveryCustomer() {
        TenantScope scope = TenantContext.current();
        return scope == null || scope.isPlatform();
    }

    /** Whether this account has ordered from the shop in scope. */
    public boolean isMine(Long customerId) {
        // THE PLATFORM CHECK COMES FIRST, deliberately. A row with no customer
        // on it at all - an address left behind by a deleted account, say - is
        // nobody's customer, so a shop must not touch it; but the platform
        // console is not asking "is this mine", it may act on the lot. Asking
        // the null question first would have refused the platform too.
        if (readsEveryCustomer()) {
            return true;
        }
        return customerId != null && orders.isCustomerOfCurrentShop(customerId);
    }

    /**
     * Refuses a shopkeeper reading or changing somebody who is not their
     * customer, as a not-found.
     */
    public void requireMine(Long customerId) {
        if (!isMine(customerId)) {
            throw new CrossShopAccessException(
                    "That customer has not ordered from this shop.");
        }
    }

    /** The shop in scope, or null when the platform is asking. */
    public Long actingShopId() {
        TenantScope scope = TenantContext.current();
        return scope == null || scope.isPlatform() ? null : scope.requireShopId();
    }
}
