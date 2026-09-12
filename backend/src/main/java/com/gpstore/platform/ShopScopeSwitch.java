package com.gpstore.platform;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

/**
 * Re-points the shop filter at the scope on the thread, mid-transaction.
 *
 * WHY THIS HAS TO EXIST. The filter is enabled when a persistence session is
 * opened, and a session lasts a transaction. That is exactly right for a
 * request that acts for one shop - which every request did, until a basket
 * could span two.
 *
 * A MULTI-SHOP CHECKOUT IS ONE TRANSACTION VISITING SEVERAL SHOPS. It has to
 * be one: the inventory locks, the coupon redemption and the idempotency
 * record either all commit or none of them do, and a customer's basket must
 * not half-succeed. So the scope changes inside a session that already has a
 * filter set to the shop the request arrived for, and every query for the
 * second shop would come back empty - which reads as "that shop has no stock"
 * rather than as the bug it is.
 *
 * ENTERING A SCOPE AND FORGETTING TO CALL THIS IS THE FAILURE MODE, so the
 * checkout loop calls it immediately after runWithin and again on the way out.
 * It cannot widen anything: it copies the scope that is already on the thread,
 * which came from the credential.
 */
@Component
public class ShopScopeSwitch {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Makes the open session agree with the scope on this thread.
     *
     * A no-op when the two already agree, which is every request that acts for
     * one shop - so the ordinary path pays nothing for this.
     */
    public void syncCurrentScope() {
        TenantFilterActivator.applyTo(entityManager);
    }

    /** Runs work in a shop's scope with the session re-pointed for its duration. */
    public <T> T within(Long shopId, java.util.concurrent.Callable<T> work) {
        return TenantContext.runWithin(TenantScope.ofShop(shopId), () -> {
            syncCurrentScope();
            try {
                return work.call();
            } finally {
                // Back to whatever the caller was in - the scope is restored by
                // runWithin, and the session has to be restored with it or the
                // next query runs under a shop nobody is in any more.
                syncCurrentScope();
            }
        });
    }
}
