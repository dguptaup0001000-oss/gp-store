package com.gpstore.repository;

import com.gpstore.entity.Payment;
import com.gpstore.enums.PaymentMethod;
import com.gpstore.enums.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderId(Long orderId);

    Optional<Payment> findByTransactionId(String transactionId);

    /** Used to auto-expire UPI payments nobody ever confirmed - see PaymentService. */
    List<Payment> findByPaymentStatusAndPaymentMethodAndPaymentDateBefore(
            PaymentStatus paymentStatus, PaymentMethod paymentMethod, LocalDateTime cutoff);
}