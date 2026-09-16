package com.gpstore.platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
class PlatformFinanceSemanticsTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2035, 2, 3, 0, 0);
    private static final LocalDateTime TO = FROM.plusDays(1);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformControlTowerService service;

    private Long deliveredOrderId;
    private Long cancelledOrderId;
    private Long paymentId;

    @BeforeEach
    void insertExactMoneyFacts() {
        Long shopId = jdbc.queryForObject("SELECT id FROM shops ORDER BY id LIMIT 1", Long.class);
        String deliveredNumber = "TOWER-MONEY-" + System.nanoTime();
        String cancelledNumber = deliveredNumber + "-C";
        Timestamp when = Timestamp.valueOf(FROM.plusHours(10));

        jdbc.update("""
                INSERT INTO orders (order_number,shop_id,order_date,total_amount,delivery_fee,
                                    order_status,payment_status,cancellation_fee)
                VALUES (?,?,?,?,?,'DELIVERED','SUCCESS',0)
                """, deliveredNumber, shopId, when,
                new BigDecimal("125.00"), new BigDecimal("25.00"));
        deliveredOrderId = jdbc.queryForObject(
                "SELECT id FROM orders WHERE order_number=?", Long.class, deliveredNumber);

        jdbc.update("""
                INSERT INTO orders (order_number,shop_id,order_date,total_amount,delivery_fee,
                                    order_status,payment_status,cancellation_fee)
                VALUES (?,?,?,?,?,'CANCELLED','FAILED',?)
                """, cancelledNumber, shopId, when,
                new BigDecimal("80.00"), BigDecimal.ZERO, new BigDecimal("7.00"));
        cancelledOrderId = jdbc.queryForObject(
                "SELECT id FROM orders WHERE order_number=?", Long.class, cancelledNumber);

        jdbc.update("""
                INSERT INTO payments (id,order_id,amount,payment_method,payment_status,active,shop_id)
                VALUES (nextval('payments_id_seq'),?,125.00,'ONLINE','PARTIALLY_REFUNDED',true,?)
                """, deliveredOrderId, shopId);
        paymentId = jdbc.queryForObject(
                "SELECT id FROM payments WHERE order_id=?", Long.class, deliveredOrderId);
        jdbc.update("""
                INSERT INTO refunds (payment_id,amount,status,created_at,settled_at,channel,
                                     refund_id,sequence_no)
                VALUES (?,20.00,'SUCCEEDED',?,?,'GATEWAY',?,1)
                """, paymentId, when, when, deliveredNumber + "-R1");
    }

    @AfterEach
    void cleanup() {
        if (paymentId != null) jdbc.update("DELETE FROM refunds WHERE payment_id=?", paymentId);
        if (paymentId != null) jdbc.update("DELETE FROM payments WHERE id=?", paymentId);
        if (deliveredOrderId != null) jdbc.update("DELETE FROM orders WHERE id=?", deliveredOrderId);
        if (cancelledOrderId != null) jdbc.update("DELETE FROM orders WHERE id=?", cancelledOrderId);
    }

    @Test
    void gmvExcludesDeliveryAndEveryMoneyCategoryRemainsSeparate() {
        PlatformControlTowerService.DashboardSummary summary = service.dashboard(FROM, TO);
        PlatformControlTowerService.FinanceSummary money = summary.finance();

        assertEquals(new BigDecimal("100.00"), money.gmv());
        assertEquals(new BigDecimal("125.00"), money.completedSales());
        assertEquals(new BigDecimal("100.00"), money.merchantProductSales());
        assertEquals(new BigDecimal("25.00"), money.deliveryCharges());
        assertEquals(new BigDecimal("20.00"), money.refunds());
        assertEquals(new BigDecimal("7.00"), money.cancellationFees());
        assertEquals(1L, summary.orderStatuses().get("REFUNDED"));
    }
}
