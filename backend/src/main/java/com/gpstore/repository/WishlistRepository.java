package com.gpstore.repository;

import com.gpstore.entity.Wishlist;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface WishlistRepository extends JpaRepository<Wishlist, Long> {

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

    /** Bulk delete for account deletion - see NotificationRepository.deleteByCustomerId. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("delete from Wishlist w where w.customer.id = :customerId")
    int deleteByCustomerIdBulk(@org.springframework.data.repository.query.Param("customerId") Long customerId);
}
