package com.gpstore.platform;

import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Reads that belong to a CUSTOMER rather than to a shop.
 *
 * TWO AXES OF PROTECTION, NOT ONE. The tenant filter keeps merchants apart:
 * Shop A must not see Shop B's orders. The ownership check keeps customers
 * apart: one customer must not see another's. They are different questions,
 * and a customer's own order history is the case where they pull in opposite
 * directions - a basket split across two kiranas is two orders, each owned by
 * a different merchant and both belonging to the same customer. Scoped to one
 * shop, "my orders" would silently hide half of what they bought.
 *
 * SO THIS WIDENS THE SCOPE, DELIBERATELY AND BY NAME, and it is safe only
 * because everything it wraps is keyed on the customer id from the verified
 * token. Nothing here relaxes an ownership check; it relaxes the MERCHANT
 * boundary for a caller who is a party to every row on the other side of it.
 *
 * IT MUST WRAP THE CALL, NOT LIVE INSIDE IT. A Hibernate filter is enabled
 * when the persistence session opens, so entering the platform scope after a
 * transaction has started changes nothing - the widening has to happen before
 * the transactional method is entered, which is why callers are controllers
 * and not services.
 */
@Component
public class CustomerOwnedRead {

    /** Runs a customer-owned read across every shop they have bought from. */
    public <T> T acrossShops(Supplier<T> read) {
        return TenantContext.runWithin(TenantScope.platform(), read::get);
    }
}
