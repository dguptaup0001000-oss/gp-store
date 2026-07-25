package com.gpstore.service;

import com.gpstore.entity.OrderItem;
import com.gpstore.entity.Product;
import com.gpstore.repository.OrderItemRepository;
import com.gpstore.repository.ProductRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class RecommendationService {

    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;

    public RecommendationService(OrderItemRepository orderItemRepository, ProductRepository productRepository) {
        this.orderItemRepository = orderItemRepository;
        this.productRepository = productRepository;
    }

    /** "Customers who bought this also bought..." - ranked by real co-purchase count, not guessed. */
    public List<Product> frequentlyBoughtWith(Long productId, int limit) {
        List<Object[]> rows = orderItemRepository.findFrequentlyBoughtWithProductId(productId);
        return resolveTopProducts(rows, limit);
    }

    /** Most-ordered products in the last N days - a time-boxed "trending" list, not an all-time leaderboard. */
    public List<Product> trending(int days, int limit) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        List<Object[]> rows = orderItemRepository.findTrendingProductIds(since);
        return resolveTopProducts(rows, limit);
    }

    /**
     * Reorder suggestions: products this specific customer has actually bought
     * before, most recently purchased first. This is real personalization from
     * their own history - not a generic guess dressed up as "for you".
     */
    public List<Product> forCustomer(Long customerId, int limit) {
        List<OrderItem> history = orderItemRepository.findByOrder_Customer_IdOrderByOrder_OrderDateDesc(customerId);

        Set<Long> seenProductIds = new LinkedHashSet<>();
        List<Product> results = new ArrayList<>();

        for (OrderItem item : history) {
            if (results.size() >= limit) break;

            Long productId = item.getProductVariant().getProduct().getId();
            if (seenProductIds.add(productId)) {
                productRepository.findById(productId).ifPresent(results::add);
            }
        }

        return results;
    }

    private List<Product> resolveTopProducts(List<Object[]> rows, int limit) {
        List<Product> results = new ArrayList<>();

        for (Object[] row : rows) {
            if (results.size() >= limit) break;

            Long productId = (Long) row[0];
            productRepository.findById(productId).ifPresent(results::add);
        }

        return results;
    }
}
