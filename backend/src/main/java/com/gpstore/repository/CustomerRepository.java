package com.gpstore.repository;

import java.util.List;
import java.util.Optional;

import com.gpstore.entity.Customer;
import com.gpstore.entity.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    // Backs NotificationService.broadcastToAll - pages through active
    // customers instead of loading the whole customer table into memory at
    // once, and filters inactive accounts in SQL instead of after fetching
    // every row.
    Page<Customer> findByActiveTrue(Pageable pageable);

    Optional<Customer> findByEmail(String email);

    Optional<Customer> findByMobileNumber(String mobileNumber);

    // Backs the "new order" push-to-admin alert (see NotificationService) -
    // every store-owner/manager account gets notified the instant an order
    // is placed, not just the customer who placed it.
    List<Customer> findByRole(Role role);

}