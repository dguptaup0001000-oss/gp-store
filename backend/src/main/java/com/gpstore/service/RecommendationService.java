package com.gpstore.service;

import com.gpstore.dto.response.ProductResponse;
import com.gpstore.entity.OrderItem;
import com.gpstore.entity.Product;
import com.gpstore.repository.OrderItemRepository;
import com.gpstore.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    @Transactional(readOnly = true)
    public List<ProductResponse> frequentlyBoughtWith(Long productId, int limit) {
        List<Object[]> rows = orderItemRepository.findFrequentlyBoughtWithProductId(productId);
        return resolveTopProducts(rows, limit);
    }

    /** Most-ordered products in the last N days - a time-boxed "trending" list, not an all-time leaderboard. */
    @Transactional(readOnly = true)
    public List<ProductResponse> trending(int days, int limit) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        List<Object[]> rows = orderItemRepository.findTrendingProductIds(since);
        return resolveTopProducts(rows, limit);
    }

    /**
     * Reorder suggestions: products this specific customer has actually bought
     * before, most recently purchased first. This is real personalization from
     * their own history - not a generic guess dressed up as "for you".
     */
    @Transactional(readOnly = true)
    public List<ProductResponse> forCustomer(Long customerId, int limit) {
        // Eager-fetched (productVariant + its product both joined in one
        // query) - see the repository method's doc comment for why this
        // matters: walking a customer's whole history to dedupe products
        // previously triggered two lazy-load queries per order item.
        List<OrderItem> history = orderItemRepository.findByCustomerIdWithProductFetched(customerId);

        Set<Long> seenProductIds = new LinkedHashSet<>();
        List<Long> orderedIds = new ArrayList<>();

        for (OrderItem item : history) {
            if (orderedIds.size() >= limit) break;

            Long productId = item.getProductVariant().getProduct().getId();
            if (seenProductIds.add(productId)) {
                orderedIds.add(productId);
            }
        }

        return fetchInRankedOrder(orderedIds);
    }

    private List<ProductResponse> resolveTopProducts(List<Object[]> rows, int limit) {
        List<Long> orderedIds = new ArrayList<>();

        for (Object[] row : rows) {
            if (orderedIds.size() >= limit) break;
            orderedIds.add((Long) row[0]);
        }

        return fetchInRankedOrder(orderedIds);
    }

    /**
     * One batched, eager-fetched query for every product ID at once (see
     * ProductRepository.findByIdIn's doc comment), instead of the previous
     * pattern of calling findById() - plus two more lazy-load queries per
     * product for its category and variants - one product at a time. For a
     * 10-50 item recommendation list, that was 20-150+ sequential round
     * trips instead of 1.
     *
     * findByIdIn doesn't preserve the input order, so results are
     * re-sorted back into the original ranking (co-purchase count, recency,
     * etc.) here.
     */
    private List<ProductResponse> fetchInRankedOrder(List<Long> orderedIds) {
        if (orderedIds.isEmpty()) {
            return List.of();
        }

        Map<Long, Product> byId = new HashMap<>();
        for (Product product : productRepository.findByIdIn(orderedIds)) {
            byId.put(product.getId(), product);
        }

        List<ProductResponse> results = new ArrayList<>();
        for (Long id : orderedIds) {
            Product product = byId.get(id);
            if (product != null) {
                results.add(ProductResponse.from(product));
            }
        }
        return results;
    }
}
