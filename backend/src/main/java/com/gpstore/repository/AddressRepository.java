package com.gpstore.repository;

import java.util.List;

import com.gpstore.entity.Address;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AddressRepository extends JpaRepository<Address, Long> {

    List<Address> findByCustomerId(Long customerId);


    /** Bulk delete for account deletion - see NotificationRepository.deleteByCustomerId. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("delete from Address a where a.customer.id = :customerId")
    int deleteByCustomerIdBulk(@org.springframework.data.repository.query.Param("customerId") Long customerId);
}