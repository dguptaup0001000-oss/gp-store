package com.gpstore.entity;

import com.gpstore.enums.PaymentMethod;
import com.gpstore.enums.PaymentStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.*;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Deliberately left EAGER: PaymentController.getPaymentById()/
    // getPaymentByOrderId()/getPaymentByTransactionId() all return raw
    // Optional<Payment> with no @Transactional on the service methods, so
    // Jackson serializes after the session closes - LAZY here throws
    // LazyInitializationException on those three endpoints. Revisit alongside
    // a DTO refactor of those lookups (getAllPayments/refundPayment already
    // map to PaymentResponse and would be safe to convert independently).
    @OneToOne(fetch = FetchType.LAZY)
        private Order order;

    private BigDecimal amount;

  @Enumerated(EnumType.STRING)
private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
private PaymentStatus paymentStatus;

    // unique=true still allows multiple NULLs in Postgres (COD and
    // not-yet-confirmed UPI payments have no transaction ID yet) - it only
    // rejects a second row with the SAME non-null value, which is exactly
    // what prevents two payments from ever claiming the same real UPI
    // transaction.
    @Column(unique = true)
    private String transactionId;

    private LocalDateTime paymentDate;

    private Boolean active;

    public Payment() {
    }

    public Long getId() {
        return id;
    }

    public Order getOrder() {
        return order;
    }

    public void setOrder(Order order) {
        this.order = order;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

 public PaymentMethod getPaymentMethod() {
    return paymentMethod;
}

public void setPaymentMethod(PaymentMethod paymentMethod) {
    this.paymentMethod = paymentMethod;
}

  public PaymentStatus getPaymentStatus() {
    return paymentStatus;
}

public void setPaymentStatus(PaymentStatus paymentStatus) {
    this.paymentStatus = paymentStatus;
}

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public LocalDateTime getPaymentDate() {
        return paymentDate;
    }

    public void setPaymentDate(LocalDateTime paymentDate) {
        this.paymentDate = paymentDate;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}