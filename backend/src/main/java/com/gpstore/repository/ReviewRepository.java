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

    /**
     * A product page's reviews, with hidden ones left out (§20).
     *
     * <p>SEPARATE FROM THE OLDER METHOD ABOVE rather than replacing it,
     * because the admin moderation list needs to see the hidden ones - that
     * is most of the point of hiding instead of deleting.
     */
    Page<Review> findByProductIdAndActiveTrueAndHiddenAtIsNullOrderByReviewDateDesc(
            Long productId, Pageable pageable);

    Page<Review> findByReportedAtIsNotNullOrderByReportedAtDesc(Pageable pageable);

    /**
     * A product's stars, counted in the database (§19).
     *
     * <p>Hidden reviews still count unless the reason says the text was never
     * a customer's opinion - the same rule the shop rating uses, and for the
     * same reason: an average that improves when you moderate is an average
     * worth moderating.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT new com.gpstore.rating.RatingTally(
                       COUNT(r), COALESCE(AVG(CAST(r.rating AS double)), 0.0),
                       SUM(CASE WHEN r.rating = 5 THEN 1 ELSE 0 END),
                       SUM(CASE WHEN r.rating = 4 THEN 1 ELSE 0 END),
                       SUM(CASE WHEN r.rating = 3 THEN 1 ELSE 0 END),
                       SUM(CASE WHEN r.rating = 2 THEN 1 ELSE 0 END),
                       SUM(CASE WHEN r.rating = 1 THEN 1 ELSE 0 END))
            FROM Review r
            WHERE r.product.id = :productId
              AND r.active = true
              AND (r.hiddenAt IS NULL OR r.hiddenReason NOT IN
                     (com.gpstore.rating.HideReason.SPAM,
                      com.gpstore.rating.HideReason.IMPERSONATION))
            """)
    com.gpstore.rating.RatingTally tallyForProduct(
            @org.springframework.data.repository.query.Param("productId") Long productId);
}
