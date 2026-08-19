package com.gpstore.repository;

import com.gpstore.entity.Wishlist;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WishlistRepository extends JpaRepository<Wishlist, Long> {

    // Eager-fetches product (which is lazy) so WishlistResponse.from()'s
    // product.getName() call doesn't lazy-load one query per wishlist item -
    // smaller-scale than the search N+1 (a wishlist is usually a handful of
    // items, not 20+), but the same avoidable shape.
    @EntityGraph(attributePaths = {"product"})
    List<Wishlist> findByCustomerId(Long customerId);

    Optional<Wishlist> findByIdAndCustomerId(Long id, Long customerId);

    /** Bulk delete for account deletion - see NotificationRepository.deleteByCustomerId. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("delete from Wishlist w where w.customer.id = :customerId")
    int deleteByCustomerIdBulk(@org.springframework.data.repository.query.Param("customerId") Long customerId);
}
