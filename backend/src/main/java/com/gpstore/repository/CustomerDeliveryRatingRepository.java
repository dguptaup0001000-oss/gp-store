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

    /**
     * The newest ratings for one customer, capped.
     *
     * A three-year regular has one of these per delivered order, so the
     * admin's customer screen shows the recent ones and reports the average
     * over all of them separately - the same rule every other list on that
     * screen follows.
     */
    List<CustomerDeliveryRating> findTop10ByCustomerIdOrderByCreatedAtDesc(Long customerId);

    /**
     * Average and count for one customer, for the admin's customer screen.
     *
     * An INTERFACE PROJECTION rather than Object[]: a two-column aggregate
     * declared as Object[] is the shape Spring Data is ambiguous about - it
     * can hand back a single-element list wrapping the row - and this query
     * had never been executed by anything, so nothing had ever proved which.
     * Named columns leave no room for it.
     *
     * getAverage() is null for a customer nobody has rated. That is the
     * honest answer and the screen renders it as "not rated yet"; zero would
     * read as a terrible customer.
     */
    @Query("select avg(r.score) as average, count(r) as total "
            + "from CustomerDeliveryRating r where r.customerId = :customerId")
    ConductSummary summaryFor(@Param("customerId") Long customerId);

    interface ConductSummary {
        Double getAverage();
        long getTotal();
    }
}
