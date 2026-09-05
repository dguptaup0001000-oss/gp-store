package com.gpstore.platform;

import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;

import java.util.Objects;

/**
 * Stamps the owning shop onto every shop-owned row as it is created.
 *
 * WHY WRITES NEED THEIR OWN RULE. The Hibernate filter (see
 * {@link ShopScopeFilter}) is a read-side device: it adds "and shop_id = ?" to
 * queries and does nothing at all to an insert. Without this listener a
 * correctly-filtered application would still write rows with no shop, which
 * are then invisible to every shop - an order a customer placed and a
 * shopkeeper can never see.
 *
 * @PrePersist, NOT A HIBERNATE EVENT LISTENER. It fires inside persist(),
 * before the insert state is snapshotted, so setting the field here is enough
 * and no provider internals are involved.
 *
 * UPDATES ARE NOT TOUCHED. An existing row already carries its shop, and
 * re-stamping on update would let a shop take ownership of a row it managed
 * to load. Moving a row between shops is not an operation this system has.
 *
 * THE @PostLoad HALF IS THE ONE THAT MATTERS FOR SECURITY. The Hibernate
 * filter rewrites queries, and there are two ways into a row that are not
 * queries: EntityManager.find() by primary key, and following an association
 * from a row you already have. Both are exactly how an id-manipulation attack
 * arrives - change 41 to 42 in the URL and the service does findById(42). So
 * every shop-owned row is checked as it is materialised, whichever way it was
 * reached, and a row from another shop stops the request there.
 */
public class TenantEntityListener {

    @PrePersist
    public void stampShop(Object row) {
        if (!(row instanceof ShopOwned owned)) {
            return;
        }
        owned.setShopId(TenantDefaults.shopIdForNewRow(owned.getShopId(), row.getClass()));
    }

    /**
     * Refuses a row that belongs to a different shop than the one in scope.
     *
     * A NULL shop_id IS ALSO REFUSED. Every shop-owned table was backfilled by
     * V46 and every insert since is stamped above, so a null here means a row
     * that got in some other way - and an unowned row is not one this shop
     * gets by default.
     *
     * Nothing is checked when the scope spans the platform, or when there is
     * no scope: the outbox worker, the refund sweep and the bootstrap all
     * legitimately touch rows from every shop, and they say so.
     */
    @PostLoad
    public void assertOwnership(Object row) {
        if (!(row instanceof ShopOwned owned)) {
            return;
        }
        TenantScope scope = TenantContext.current();
        if (scope == null || scope.isPlatform()) {
            return;
        }
        if (!Objects.equals(owned.getShopId(), scope.shopId())) {
            throw new CrossShopAccessException(row.getClass(), owned.getShopId(), scope.shopId());
        }
    }
}
