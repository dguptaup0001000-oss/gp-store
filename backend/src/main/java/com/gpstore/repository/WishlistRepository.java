package com.gpstore.repository;

import com.gpstore.entity.Wishlist;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface WishlistRepository extends JpaRepository<Wishlist, Long> {

    /**
     * Saved items for products THIS shop lists.
     *
     * <p>WAS findAll(). Every customer's wishlist across the whole marketplace,
     * which is the demand signal for every competitor's catalogue handed to
     * whoever asked. Narrowed by the same EXISTS the merchant's product list
     * and review list use: {@code ShopProductVariant} is shop-owned, so joining
     * through it applies the tenant filter.
     */
    @Query("""
            SELECT w FROM Wishlist w
            WHERE EXISTS (
                SELECT 1 FROM ProductVariant v, ShopProductVariant l
                WHERE v.product = w.product AND l.productVariantId = v.id
            )
            ORDER BY w.id DESC
            """)
    Page<Wishlist> findAllForCurrentShopShelf(Pageable pageable);

    /**
     * One customer's saved items, limited to what this shop lists.
     *
     * <p>Same narrowing as {@link #findAllForCurrentShopShelf}, asked about one
     * person - for the shopkeeper's customer screen. A shop advising a customer
     * about a saved item needs the items it can actually sell them; the rest of
     * the list is that customer's business with other shops.
     */
    @Query("""
            SELECT w FROM Wishlist w
            WHERE w.customer.id = :customerId
              AND EXISTS (
                SELECT 1 FROM ProductVariant v, ShopProductVariant l
                WHERE v.product = w.product AND l.productVariantId = v.id
            )
            ORDER BY w.id DESC
            """)
    java.util.List<Wishlist> findByCustomerIdOnCurrentShopShelf(
            @org.springframework.data.repository.query.Param("customerId") Long customerId);


    // Eager-fetches product (which is lazy) so WishlistResponse.from()'s
    // product.getName() call doesn't lazy-load one query per wishlist item -
    // smaller-scale than the search N+1 (a wishlist is usually a handful of
    // items, not 20+), but the same avoidable shape.
    @EntityGraph(attributePaths = {"product"})
    /**
     * The customer's wishlist, with each product AND its variants and category
     * already loaded.
     *
     * THE FETCH JOINS ARE NOT AN OPTIMISATION HERE, THEY ARE THE FIX.
     * WishlistResponse now nests a full ProductResponse, which reads the
     * product's category and variants. Wishlist.product is LAZY, so the plain
     * derived query this replaced produced one SELECT for the wishlist and
     * then three more per row - product, category, variants - which is 61
     * queries for a twenty-item wishlist.
     *
     * `distinct` because joining a to-many (variants) multiplies the wishlist
     * rows by the number of variants: a product with three sizes would
     * otherwise appear three times in the customer's wishlist.
     *
     * LEFT joins throughout: a wishlist row whose product was deleted, or a
     * product with no variants yet, must still come back - as an entry the
     * client can skip - rather than silently vanishing from the list.
     */
    @Query("""
           select distinct w from Wishlist w
           left join fetch w.product p
           left join fetch p.category
           left join fetch p.variants
           where w.customer.id = :customerId
           order by w.id desc
           """)
    List<Wishlist> findByCustomerId(@Param("customerId") Long customerId);

    Optional<Wishlist> findByIdAndCustomerId(Long id, Long customerId);

    Optional<Wishlist> findByCustomerIdAndProductId(Long customerId, Long productId);

    /** Bulk delete for account deletion - see NotificationRepository.deleteByCustomerId. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("delete from Wishlist w where w.customer.id = :customerId")
    int deleteByCustomerIdBulk(@org.springframework.data.repository.query.Param("customerId") Long customerId);
}
