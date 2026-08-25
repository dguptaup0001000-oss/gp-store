package com.gpstore.repository;

import com.gpstore.entity.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    Page<Review> findByProductIdAndActiveTrueOrderByReviewDateDesc(Long productId, Pageable pageable);

    // Eager-fetches product - "my reviews" spans different products per row
    // (unlike getForProduct's single-product page, where Hibernate's
    // persistence context already dedupes the one lazy load across every
    // row), so without this each review lazy-loads its own product separately.
    @EntityGraph(attributePaths = {"product"})
    List<Review> findByCustomerId(Long customerId);

    @EntityGraph(attributePaths = {"product"})
    List<Review> findByCustomerIdOrderByReviewDateDesc(Long customerId, Pageable pageable);

    Optional<Review> findByCustomerIdAndProductId(Long customerId, Long productId);

    Optional<Review> findByIdAndCustomerId(Long id, Long customerId);
}
