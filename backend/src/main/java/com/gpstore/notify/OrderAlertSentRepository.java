package com.gpstore.notify;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderAlertSentRepository extends JpaRepository<OrderAlertSent, Long> {

    Optional<OrderAlertSent> findByOrderIdAndKind(Long orderId, String kind);

    boolean existsByOrderIdAndKind(Long orderId, String kind);
}
