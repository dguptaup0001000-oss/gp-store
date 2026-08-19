package com.gpstore.repository;

import com.gpstore.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificationRepository
        extends JpaRepository<Notification, Long> {

    List<Notification> findByCustomerId(Long customerId);

    List<Notification> findByCustomerIdOrderBySentAtDesc(Long customerId);

    Page<Notification> findByCustomerIdOrderBySentAtDesc(Long customerId, Pageable pageable);

    /**
     * Paginated deliberately. A single order accumulates a notification per
     * status change, plus any resend, so this grows with an order's history
     * rather than being naturally bounded - and nothing about the old
     * List-returning signature stopped a caller loading all of them.
     */
    Page<Notification> findByOrderId(Long orderId, Pageable pageable);

    long countByCustomerIdAndIsReadFalse(Long customerId);

    /**
     * Marks every unread notification for one customer as read in a single
     * statement.
     *
     * Replaces a load-all-then-loop-then-saveAll implementation, which
     * pulled every notification a customer had ever received into JVM
     * memory, dirtied each one, and issued an UPDATE per row - unbounded in
     * both memory and query count, and growing for the life of the account.
     *
     * The `and isRead = false` predicate is not just an optimisation: it
     * keeps the statement's write set to rows that actually change, so
     * repeated calls (a double-tapped "Mark all read") touch nothing the
     * second time instead of rewriting the customer's whole history.
     *
     * clearAutomatically/flushAutomatically keep the persistence context
     * honest - without them, entities already loaded in this transaction
     * would still report their stale isRead value after this runs.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Notification n set n.isRead = true "
            + "where n.customer.id = :customerId and n.isRead = false")
    int markAllAsReadForCustomer(@Param("customerId") Long customerId);
}