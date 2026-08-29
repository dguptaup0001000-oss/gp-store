package com.gpstore.repository;

import java.util.List;
import java.util.Optional;

import com.gpstore.entity.Customer;
import com.gpstore.entity.Role;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    // Backs NotificationService.broadcastToAll - pages through active
    // customers instead of loading the whole customer table into memory at
    // once, and filters inactive accounts in SQL instead of after fetching
    // every row.
    Page<Customer> findByActiveTrue(Pageable pageable);

    long countByActiveTrue();

    /**
     * Locks the customer row for the duration of the transaction - used by
     * CartService's mutation methods to serialize concurrent cart
     * operations for one customer. Locking the customer row rather than the
     * cart row is deliberate: unlike a cart, the customer row is guaranteed
     * to already exist (created at registration) before any cart mutation
     * is possible, so this closes BOTH the item-quantity race AND the rarer
     * "this customer's very first add-to-cart ever, hit twice at once"
     * race, where two concurrent requests would otherwise both find no
     * Cart row yet and both try to INSERT one.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c where c.id = :id")
    Optional<Customer> findByIdForUpdate(@Param("id") Long id);

    Optional<Customer> findByEmail(String email);

    Optional<Customer> findByEmailIgnoreCase(String email);

    Optional<Customer> findByMobileNumber(String mobileNumber);

    // Backs the "new order" push-to-admin alert (see NotificationService) -
    // every store-owner/manager account gets notified the instant an order
    // is placed, not just the customer who placed it.
    List<Customer> findByRole(Role role);

}