package com.gpstore.repository;

import com.gpstore.entity.CustomerDeliveryRating;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CustomerDeliveryRatingRepository
        extends JpaRepository<CustomerDeliveryRating, Long> {

    Optional<CustomerDeliveryRating> findByOrderId(Long orderId);

    List<CustomerDeliveryRating> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    /** Average and count for one customer, for the admin's customer screen. */
    @Query("select avg(r.score), count(r) from CustomerDeliveryRating r where r.customerId = :customerId")
    Object[] averageAndCount(@Param("customerId") Long customerId);
}
