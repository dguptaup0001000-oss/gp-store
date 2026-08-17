package com.gpstore.repository;

import com.gpstore.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository
        extends JpaRepository<Notification, Long> {

    List<Notification> findByCustomerId(Long customerId);

    List<Notification> findByCustomerIdOrderBySentAtDesc(Long customerId);

    Page<Notification> findByCustomerIdOrderBySentAtDesc(Long customerId, Pageable pageable);

    List<Notification> findByOrderId(Long orderId);

    long countByCustomerIdAndIsReadFalse(Long customerId);
}