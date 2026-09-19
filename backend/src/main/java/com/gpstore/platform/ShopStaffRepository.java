package com.gpstore.platform;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ShopStaffRepository extends JpaRepository<ShopStaff, Long> {

    /**
     * Every live membership for one account, NATIVELY so the shop filter
     * cannot narrow it.
     *
     * THE ONE PLACE THE FILTER MUST NOT APPLY, and it is worth being explicit
     * about why rather than relying on a native query's side effect. This
     * query answers "which shops may this person work in", which is the
     * question the scope itself is derived from - running it under a shop
     * scope would filter the answer to the shop we are trying to determine,
     * and a manager with two shops would only ever see the one they were
     * already in.
     *
     * It reads nothing a caller sent: the customer id comes from the verified
     * token, and the rows come from the database.
     */
    @Query(value = "SELECT shop_id FROM shop_staff "
            + "WHERE customer_id = :customerId AND active = true "
            + "ORDER BY is_default DESC, shop_id ASC",
            nativeQuery = true)
    List<Long> shopIdsFor(@Param("customerId") Long customerId);

    @Query(value = "SELECT shop_id FROM shop_staff "
            + "WHERE customer_id = :customerId AND active = true AND is_default = true "
            + "LIMIT 1",
            nativeQuery = true)
    Optional<Long> defaultShopIdFor(@Param("customerId") Long customerId);

    List<ShopStaff> findByCustomerId(Long customerId);

    Optional<ShopStaff> findByShopIdAndCustomerId(Long shopId, Long customerId);

    /**
     * Every account that may work in one shop, NATIVELY so the shop filter
     * cannot narrow it.
     *
     * <p>WHO A NEW ORDER IS FOR. The dispatcher runs after the order's
     * transaction has committed, on a thread with no shop scope of its own -
     * there is no request and no credential behind it - so a filtered query
     * would return nothing at all and the shop would never be told. The shop id
     * is not taken from a client: it is {@code order.shopId}, written by the
     * server when the order was created.
     *
     * <p>It selects ONE COLUMN of account ids for a shop the caller has already
     * established, so nothing about any other shop can travel through it.
     */
    @Query(value = "SELECT customer_id FROM shop_staff "
            + "WHERE shop_id = :shopId AND active = true "
            + "ORDER BY is_default DESC, customer_id ASC",
            nativeQuery = true)
    List<Long> staffIdsFor(@Param("shopId") Long shopId);
}
