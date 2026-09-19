package com.gpstore.platform;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ShopRepository extends JpaRepository<Shop, Long> {

    Optional<Shop> findByCode(String code);

    List<Shop> findByMerchantId(Long merchantId);

    List<Shop> findByStatus(ShopStatus status);

    List<Shop> findByIsDemoTrue();

    /**
     * Whether this deployment is operating more than one real storefront.
     *
     * <p>The production database can gain its second shop before an external
     * {@code platform.mode} setting is changed. Product isolation is a data
     * boundary, so it must follow the shops that actually exist rather than
     * trusting deployment configuration to be updated at exactly the same
     * moment.
     */
    long countByDeletedAtIsNull();

    /**
     * Shops close enough to be worth measuring properly, by a box.
     *
     * <p>WHY A BOX AND NOT THE REAL DISTANCE. Discovery used to call
     * {@code findAll()} and measure every shop in Java. At two thousand shops
     * that is two thousand hydrated entities to answer a question about a few
     * streets - a load run counted 62,288,692 rows returned across 29,437
     * discovery requests - and it got worse with every shop that joined the
     * marketplace. This cuts the rows down before they leave the database.
     *
     * <p>IT IS DELIBERATELY A SUPERSET, AND THAT IS THE SAFETY PROPERTY. A
     * square drawn around a circle always contains the circle, so every shop
     * the exact haversine would have accepted is still in this result; the box
     * only lets through extra ones, which the caller then measures properly
     * and discards. That is what makes this a speed change and not a
     * behaviour change: the answer is decided by the same formula as before.
     *
     * <p>THE DEGREES-PER-KM FIGURES ARE PASSED IN, NOT COMPUTED HERE, because
     * a degree of longitude shrinks towards the poles and the shrinkage
     * depends on the customer's latitude. They are also deliberately
     * generous - a slightly too-large box costs a few extra rows, a slightly
     * too-small one silently hides a shop that would have delivered.
     *
     * <p>NO VISIBILITY RULE LIVES HERE. Whether a customer may see a shop at
     * all is {@code ShopStatus.isVisibleToCustomers} plus the active flag, and
     * it stays in ShopDiscovery where it has always been. Two copies of that
     * rule is one copy too many, and a query is the worse place to keep it.
     */
    @Query("select s from Shop s "
            + "where s.deletedAt is null "
            + "and s.latitude is not null and s.longitude is not null "
            + "and s.maxDeliveryRadiusKm is not null "
            + "and abs(s.latitude - :lat) <= :latDegreesPerKm * s.maxDeliveryRadiusKm "
            + "and abs(s.longitude - :lng) <= :lngDegreesPerKm * s.maxDeliveryRadiusKm")
    List<Shop> findWithinOwnRadiusBox(@Param("lat") double lat,
                                      @Param("lng") double lng,
                                      @Param("latDegreesPerKm") double latDegreesPerKm,
                                      @Param("lngDegreesPerKm") double lngDegreesPerKm);

    /**
     * The same box, sized by the SEARCH radius rather than by each shop's own.
     *
     * <p>Separate because the two questions are separate, exactly as
     * {@code shopsServing} and {@code shopsWithin} are: one asks who will come
     * to the customer, the other asks who is near them. A shop with no radius
     * declared is included here - it is still a shop the customer can see -
     * and whether it delivers is answered afterwards, per shop, as before.
     */
    @Query("select s from Shop s "
            + "where s.deletedAt is null "
            + "and s.latitude is not null and s.longitude is not null "
            + "and abs(s.latitude - :lat) <= :latSpan "
            + "and abs(s.longitude - :lng) <= :lngSpan")
    List<Shop> findWithinBox(@Param("lat") double lat,
                             @Param("lng") double lng,
                             @Param("latSpan") double latSpan,
                             @Param("lngSpan") double lngSpan);
}
