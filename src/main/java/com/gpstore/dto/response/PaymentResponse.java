package com.gpstore.dto.response;

import com.gpstore.entity.Payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The raw Payment entity's nested Order field has no JSON protection at
 * all - not a crash risk (Order's own fields already terminate safely
 * elsewhere), but it would drag the full order and every item along with
 * every single payment record, which is wasteful for an admin list that
 * just needs to know which order and customer a payment belongs to.
 */
public class PaymentResponse {

    private final Long id;
    private final Long orderId;
    private final String orderNumber;
    private final String customerName;
    private final BigDecimal amount;
    private final String paymentMethod;
    private final String paymentStatus;
    private final String transactionId;
    private final LocalDateTime paymentDate;

    public PaymentResponse(Long id, Long orderId, String orderNumber, String customerName, BigDecimal amount,
                            String paymentMethod, String paymentStatus, String transactionId,
                            LocalDateTime paymentDate) {
        this.id = id;
        this.orderId = orderId;
        this.orderNumber = orderNumber;
        this.customerName = customerName;
        this.amount = amount;
        this.paymentMethod = paymentMethod;
        this.paymentStatus = paymentStatus;
        this.transactionId = transactionId;
        this.paymentDate = paymentDate;
    }

    public static PaymentResponse from(Payment payment) {
        var order = payment.getOrder();
        var customer = order != null ? order.getCustomer() : null;

        return new PaymentResponse(
                payment.getId(),
                order != null ? order.getId() : null,
                order != null ? order.getOrderNumber() : null,
                customer != null ? customer.getFullName() : null,
                payment.getAmount(),
                payment.getPaymentMethod() != null ? payment.getPaymentMethod().name() : null,
                payment.getPaymentStatus() != null ? payment.getPaymentStatus().name() : null,
                payment.getTransactionId(),
                payment.getPaymentDate()
        );
    }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public String getOrderNumber() { return orderNumber; }
    public String getCustomerName() { return customerName; }
    public BigDecimal getAmount() { return amount; }
    public String getPaymentMethod() { return paymentMethod; }
    public String getPaymentStatus() { return paymentStatus; }
    public String getTransactionId() { return transactionId; }
    public LocalDateTime getPaymentDate() { return paymentDate; }
}
