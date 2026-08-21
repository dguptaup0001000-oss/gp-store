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

    /**
     * Reuses the same trending query the customer-facing recommendations use
     * - one source of truth.
     *
     * TWO THINGS FIXED HERE, both the same shape as the customer path.
     *
     * The limit now goes to the DATABASE. This used to fetch the entire
     * ranked leaderboard for the window and count to `limit` in a Java loop,
     * so an admin asking for the top ten pulled back one row per distinct
     * product ever ordered.
     *
     * And the product lookup was an N+1: findById inside the loop, one round
     * trip per row. Batched into a single findByIdIn, which is what every
     * other ranked list in this codebase already does.
     *
     * Unlike the customer-facing lists this deliberately does NOT filter on
     * active - an admin looking at what sold last month needs to see a
     * product they have since withdrawn, which is exactly the kind of thing
     * they withdraw it for.
     */
    public List<Map<String, Object>> getTopProducts(int days, int limit) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        List<Object[]> rows = orderItemRepository.findTrendingProductIds(
                since, org.springframework.data.domain.PageRequest.of(0, Math.max(1, limit)));

        List<Long> productIds = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            productIds.add((Long) row[0]);
        }
        if (productIds.isEmpty()) {
            return List.of();
        }

        Map<Long, com.gpstore.entity.Product> byId = new HashMap<>();
        for (com.gpstore.entity.Product product : productRepository.findByIdIn(productIds)) {
            byId.put(product.getId(), product);
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (Object[] row : rows) {
            com.gpstore.entity.Product product = byId.get((Long) row[0]);
            if (product == null) {
                continue;
            }
            Map<String, Object> entry = new HashMap<>();
            entry.put("productId", product.getId());
            entry.put("productName", product.getName());
            entry.put("unitsSold", row[1]);
            results.add(entry);
        }
        return results;
    }

    public long getLowStockCount() {
        return inventoryRepository.countLowStock();
    }
}
