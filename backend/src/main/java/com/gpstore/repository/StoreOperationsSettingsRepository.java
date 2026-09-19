package com.gpstore.repository;

import com.gpstore.entity.StoreOperationsSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StoreOperationsSettingsRepository extends JpaRepository<StoreOperationsSettings, Long> {

    /**
     * This shop's settings row.
     *
     * BY SHOP, NOT BY A CONSTANT. These were singletons found by findById(1),
     * which is both wrong with more than one shop and invisible to the
     * Hibernate filter - a load by primary key is not a query. Finding by
     * shop_id makes the predicate explicit, so it holds whether or not a
     * filter happens to be enabled on the session.
     */
    Optional<StoreOperationsSettings> findByShopId(Long shopId);

    /**
     * These shops' settings rows, in one query instead of one query each.
     *
     * <p>WHY THIS EXISTS. The marketplace's discovery screen answers
     * open/closed for every storefront that will deliver to a customer. Read
     * one shop at a time that was two queries per shop; a load run counted
     * 7,019,112 executions of the single-shop form across 29,437 requests, and
     * because each one is a round trip taken while holding a pooled
     * connection, twenty connections were enough to stall the whole
     * application long before either the database or the CPU was busy.
     *
     * <h2>Why it is native, and why that is safe here</h2>
     *
     * <p>THE TENANT FILTER WOULD DEFEAT A BATCH, NOT PROTECT IT. JPQL against
     * a {@link com.gpstore.platform.ShopOwned} entity has the shop filter
     * ANDed into it, which narrows any query to the ONE shop in scope - so a
     * JPQL "in (:shopIds)" would silently return at most one row and the
     * screen would be wrong rather than slow. A native query is not filtered,
     * so the narrowing has to be written down, and here it is: {@code shop_id
     * IN (:shopIds)}, visible in the text, with no scope it can inherit.
     *
     * <p>THIS DOES NOT WIDEN WHAT ANY CALLER MAY SEE. The caller supplies the
     * ids, and its only source of ids is ShopDiscovery, which returns the
     * storefronts a customer is allowed to see. The rows themselves are
     * storefront facts - open now, paused until four, today's closure
     * message - already shown to any customer who opens that shop. What is
     * NOT here is anything a merchant owns privately; this is not a way to
     * read another shop's orders, takings or catalogue, and it must never
     * grow into one.
     *
     * <p>CALLERS MUST KEY BY {@code getShopId()} AND NEVER ASSUME AN ORDER.
     * A batch hands back rows for many shops at once, so the one guard the
     * single-shop form gave away for free - that whatever came back belonged
     * to the shop you asked about - is now the caller's to keep.
     * MarketplaceBatchedStatusTest holds it to that.
     */
    @Query(value = "SELECT * FROM store_operations_settings WHERE shop_id IN (:shopIds)",
            nativeQuery = true)
    List<StoreOperationsSettings> findForShops(@Param("shopIds") Collection<Long> shopIds);
}
