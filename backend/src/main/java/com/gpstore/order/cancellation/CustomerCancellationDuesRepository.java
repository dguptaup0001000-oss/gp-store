package com.gpstore.order.cancellation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Reads stay inside the shop filter deliberately.
 *
 * <p>None of these methods take a shop id. The scope comes from the
 * credential, as everywhere else - a query that accepted a shop id would be
 * a query a caller could point at somebody else's debts.
 */
public interface CustomerCancellationDuesRepository
        extends JpaRepository<CustomerCancellationDue, Long> {

    /** The one debt this cancellation created, if it created one. */
    Optional<CustomerCancellationDue> findByOrderId(Long orderId);

    List<CustomerCancellationDue> findByCustomerIdAndStatusOrderByCreatedAtAsc(
            Long customerId, DueStatus status);

    List<CustomerCancellationDue> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    /** Everything this shop is still owed. Shop-scoped by the filter, not by an argument. */
    List<CustomerCancellationDue> findByStatusOrderByCreatedAtAsc(DueStatus status);
}
