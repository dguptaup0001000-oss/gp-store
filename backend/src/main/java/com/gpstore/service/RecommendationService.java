package com.gpstore.service;

import com.gpstore.dto.response.ProductResponse;
import com.gpstore.entity.OrderItem;
import com.gpstore.entity.Product;
import com.gpstore.repository.OrderItemRepository;
import com.gpstore.repository.ProductRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
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

    /**
     * How many recent order items to consider when building "buy again"
     * suggestions. Sized so that even a basket-heavy customer's last several
     * orders are covered, while keeping the query cost constant instead of
     * growing with account age.
     */
    private static final int RECENT_HISTORY_CAP = 200;

    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;

    public RecommendationService(OrderItemRepository orderItemRepository, ProductRepository productRepository) {
        this.orderItemRepository = orderItemRepository;
        this.productRepository = productRepository;
    }

    /** "Customers who bought this also bought..." - ranked by real co-purchase count, not guessed. */
    @Transactional(readOnly = true)
    @Cacheable("frequentlyBought")
    public List<ProductResponse> frequentlyBoughtWith(Long productId, int limit) {
        List<Object[]> rows = orderItemRepository.findFrequentlyBoughtWithProductId(
                productId, PageRequest.of(0, candidatePoolFor(limit)));
        return resolveTopProducts(rows, limit);
    }

    /**
     * Most-ordered products in the last N days - a time-boxed "trending"
     * list, not an all-time leaderboard.
     *
     * CACHED, AND IT IS THE ONLY BROWSE PATH THAT WAS NOT. productFeed,
     * categoryProducts, productSearch, newArrivals, brands and categories are
     * all @Cacheable; this one ran a GROUP BY over every order item in the
     * window on EVERY home screen open, by every customer, uncached. Under
     * the measured 750-VU browse load that is one aggregation per request on
     * a 0.5 vCPU instance.
     *
     * Caching it is safe in a way caching a price or a stock count would not
     * be: "what sold most in the last seven days" is an approximation by
     * construction, and the ten-minute default TTL cannot make it wrong in a
     * way anyone can perceive. Nothing here is used to price or reserve
     * anything.
     *
     * The limit is now applied by the database rather than by the loop in
     * resolveTopProducts, so this returns ten rows instead of the entire
     * ranked leaderboard for the window.
     */
    @Transactional(readOnly = true)
    @Cacheable("trending")
    public List<ProductResponse> trending(int days, int limit) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        List<Object[]> rows = orderItemRepository.findTrendingProductIds(
                since, PageRequest.of(0, candidatePoolFor(limit)));
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
        // Hard cap rather than the customer's whole history. Only the most
        // recent purchases matter for "buy again" suggestions, and the old
        // unbounded query got slower the longer someone had been a
        // customer. RECENT_HISTORY_CAP is generous relative to `limit` so
        // dedupe still has plenty to work with.
        List<OrderItem> history = orderItemRepository.findByCustomerIdWithProductFetched(
                customerId, org.springframework.data.domain.PageRequest.of(0, RECENT_HISTORY_CAP));

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

    /**
     * A few more candidates than the caller asked for.
     *
     * The database now applies the limit, which is the fix - but a product
     * can be deactivated after it was ordered, and those are dropped below.
     * Asking for exactly `limit` rows would then quietly return a short row
     * of recommendations. A small multiple keeps the result set bounded and
     * still survives a handful of retired products.
     */
    private static int candidatePoolFor(int limit) {
        return Math.min(limit * 3, 150);
    }

    private List<ProductResponse> resolveTopProducts(List<Object[]> rows, int limit) {
        List<Long> orderedIds = new ArrayList<>();
        for (Object[] row : rows) {
            orderedIds.add((Long) row[0]);
        }

        // Trimmed AFTER the active filter, not before, or a deactivated
        // product would take a slot and leave the row one short.
        List<ProductResponse> ranked = fetchInRankedOrder(orderedIds);
        return ranked.size() > limit ? ranked.subList(0, limit) : ranked;
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
            // ACTIVE ONLY. These lists are built from order history, and a
            // product that sold well last week may have been retired since -
            // findByIdIn does not filter, so without this check trending,
            // "bought together" and "buy again" would all keep advertising
            // something the shop has deliberately withdrawn. Caching these
            // made that stick for the whole TTL rather than one request.
            if (product != null && Boolean.TRUE.equals(product.getActive())) {
                results.add(ProductResponse.from(product));
            }
        }
        return results;
    }
}
