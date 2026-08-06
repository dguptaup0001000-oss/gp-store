package com.gpstore.repository;

import com.gpstore.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByCustomerId(Long customerId);

    List<Order> findByOrderStatus(com.gpstore.enums.OrderStatus status);

    List<Order> findByOrderStatusAndOrderDateBefore(com.gpstore.enums.OrderStatus status, java.time.LocalDateTime cutoff);

    @org.springframework.data.jpa.repository.Query("SELECT MAX(o.orderNumber) FROM Order o WHERE o.orderNumber LIKE CONCAT(:prefix, '%')")
    String findMaxOrderNumberWithPrefix(@Param("prefix") String prefix);

    @org.springframework.data.jpa.repository.Query(value = "SELECT nextval('order_number_seq')::int WHERE :prefix IS NOT NULL", nativeQuery = true)
    Integer getNextOrderSequence(@org.springframework.data.repository.query.Param("prefix") String prefix);

    List<Order> findByCustomerIdOrderByOrderDateDesc(Long customerId);

    /**
     * Real revenue only - excludes CANCELLED orders so a cancelled order
     * doesn't inflate reported sales. Returns null (not zero) via COALESCE
     * guard in the service if there are no matching orders.
     */
    @Query("select coalesce(sum(o.totalAmount), 0) from Order o " +
            "where o.orderDate >= :from and o.orderDate <= :to and o.orderStatus <> 'CANCELLED'")
    BigDecimal sumRevenueBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("select count(o) from Order o " +
            "where o.orderDate >= :from and o.orderDate <= :to and o.orderStatus <> 'CANCELLED'")
    long countOrdersBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("select count(o) from Order o where o.orderDate >= :from and o.orderDate <= :to and o.orderStatus = 'CANCELLED'")
    long countCancelledBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Order count grouped by current status - the "what's in the pipeline right now" view. */
    @Query("select o.orderStatus as status, count(o) as cnt from Order o group by o.orderStatus")
    List<Object[]> countByStatus();

}