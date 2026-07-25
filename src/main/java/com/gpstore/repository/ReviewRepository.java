package com.gpstore.repository;

import com.gpstore.entity.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    Page<Review> findByProductIdAndActiveTrueOrderByReviewDateDesc(Long productId, Pageable pageable);

    List<Review> findByCustomerId(Long customerId);

    Optional<Review> findByCustomerIdAndProductId(Long customerId, Long productId);

    Optional<Review> findByIdAndCustomerId(Long id, Long customerId);

    /** Used by "Shop by Brand" sorting - one query for every product's average rating, rather than N+1 per-product queries. */
    @Query("select r.product.id as productId, avg(r.rating) as avgRating from Review r where r.active = true group by r.product.id")
    List<Object[]> findAverageRatingsByProduct();
}
