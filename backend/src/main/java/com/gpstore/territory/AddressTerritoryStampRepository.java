package com.gpstore.territory;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * The stamps of the shop in scope. Every query here is narrowed by the tenant
 * filter because {@link AddressTerritoryStamp} is shop-owned - none of them
 * names a shop, and none of them can reach another shop's row.
 */
public interface AddressTerritoryStampRepository
        extends JpaRepository<AddressTerritoryStamp, Long> {

    /** This shop's stamp for one address, if it has ever made one. */
    @Query("SELECT s FROM AddressTerritoryStamp s WHERE s.addressId = :addressId")
    Optional<AddressTerritoryStamp> findForAddress(@Param("addressId") Long addressId);

    /**
     * One NAMED shop's stamp for an address.
     *
     * <p>THE ONLY QUERY HERE THAT SAYS A SHOP OUT LOUD, and it exists for the
     * platform console: a platform administrator pinning a house has no shop
     * on the thread, so the filter narrows nothing and
     * {@link #findForAddress} would hand back whichever shop's row happened to
     * be first - and then a pin meant for shop A would be written onto shop
     * B's. The shop here is never a parameter a caller invents: it comes from
     * the TERRITORY being pinned into, which is itself shop-owned.
     */
    @Query("SELECT s FROM AddressTerritoryStamp s "
            + "WHERE s.addressId = :addressId AND s.shopId = :shopId")
    Optional<AddressTerritoryStamp> findForAddressInShop(@Param("addressId") Long addressId,
                                                         @Param("shopId") Long shopId);

    /** This shop's stamps for a page of addresses - for the bulk re-resolve. */
    @Query("SELECT s FROM AddressTerritoryStamp s WHERE s.addressId IN :addressIds")
    List<AddressTerritoryStamp> findForAddresses(
            @Param("addressIds") java.util.Collection<Long> addressIds);

    /** Every stamp this shop has made, oldest first. */
    @Query("SELECT s FROM AddressTerritoryStamp s ORDER BY s.id ASC")
    Page<AddressTerritoryStamp> findAllForCurrentShop(Pageable pageable);

    /**
     * THERE IS DELIBERATELY NO BULK DELETE HERE.
     *
     * <p>An earlier draft had one, to forget every shop's stamp when a
     * customer moved their pin. A bulk {@code DELETE} is not rewritten by
     * Hibernate's shop filter - filters apply to selects, not to bulk
     * statements - so it reached every shop's row whatever scope was on the
     * thread, which is precisely the kind of statement this whole change
     * exists to remove. A stamp now records the point it was resolved from and
     * each shop notices staleness on its own row, so nothing needs to reach
     * across shops at all. An address that is deleted takes its stamps with it
     * through the foreign key.
     */
}
