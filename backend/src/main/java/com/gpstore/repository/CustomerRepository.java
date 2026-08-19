package com.gpstore.repository;

import java.util.List;
import java.util.Optional;

import com.gpstore.entity.Customer;
import com.gpstore.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByEmail(String email);

    Optional<Customer> findByMobileNumber(String mobileNumber);

    // Backs the "new order" push-to-admin alert (see NotificationService) -
    // every store-owner/manager account gets notified the instant an order
    // is placed, not just the customer who placed it.
    List<Customer> findByRole(Role role);

}