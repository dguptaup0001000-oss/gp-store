package com.gpstore.service;

import com.gpstore.entity.Product;
import com.gpstore.repository.InventoryRepository;
import com.gpstore.repository.OrderItemRepository;
import com.gpstore.repository.OrderRepository;
import com.gpstore.repository.ProductRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AnalyticsService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;

    public AnalyticsService(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                             ProductRepository productRepository, InventoryRepository inventoryRepository) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
    }

    /**
     * Revenue deliberately excludes CANCELLED orders - a cancelled order was
     * never real revenue and including it would overstate how the business is
     * actually doing.
     */
    public Map<String, Object> getSalesSummary(int days) {
        LocalDateTime from = LocalDateTime.now().minusDays(days);
        LocalDateTime to = LocalDateTime.now();

        BigDecimal revenue = orderRepository.sumRevenueBetween(from, to);
        long orderCount = orderRepository.countOrdersBetween(from, to);
        long cancelledCount = orderRepository.countCancelledBetween(from, to);

        BigDecimal averageOrderValue = orderCount == 0
                ? BigDecimal.ZERO
                : revenue.divide(BigDecimal.valueOf(orderCount), 2, RoundingMode.HALF_UP);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("periodDays", days);
        summary.put("revenue", revenue);
        summary.put("orderCount", orderCount);
        summary.put("cancelledCount", cancelledCount);
        summary.put("averageOrderValue", averageOrderValue);
        return summary;
    }

    /** How many orders are currently in each stage - the "what needs attention right now" view. */
    public Map<String, Long> getOrderStatusBreakdown() {
        Map<String, Long> breakdown = new LinkedHashMap<>();
        for (Object[] row : orderRepository.countByStatus()) {
            breakdown.put(row[0].toString(), (Long) row[1]);
        }
        return breakdown;
    }

    /** Reuses the same trending query the customer-facing recommendations use - one source of truth. */
    public List<Map<String, Object>> getTopProducts(int days, int limit) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        List<Object[]> rows = orderItemRepository.findTrendingProductIds(since);

        List<Map<String, Object>> results = new ArrayList<>();
        for (Object[] row : rows) {
            if (results.size() >= limit) break;

            Long productId = (Long) row[0];
            Long unitsSold = (Long) row[1];

            productRepository.findById(productId).ifPresent(product -> {
                Map<String, Object> entry = new HashMap<>();
                entry.put("productId", product.getId());
                entry.put("productName", product.getName());
                entry.put("unitsSold", unitsSold);
                results.add(entry);
            });
        }
        return results;
    }

    public long getLowStockCount() {
        return inventoryRepository.countLowStock();
    }
}
