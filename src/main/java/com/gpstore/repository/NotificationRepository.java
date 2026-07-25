package com.gpstore.repository;

import com.gpstore.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository
        extends JpaRepository<Notification, Long> {

    List<Notification> findByCustomerId(Long customerId);

    List<Notification> findByCustomerIdOrderBySentAtDesc(Long customerId);

    List<Notification> findByOrderId(Long orderId);
}