package com.gpstore.service;

import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import com.gpstore.exception.BadRequestException;
import com.gpstore.repository.OrderItemRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ReviewRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final ReviewRepository reviewRepository;
    private final OrderItemRepository orderItemRepository;

    public ProductService(
            ProductRepository productRepository,
            ReviewRepository reviewRepository,
            OrderItemRepository orderItemRepository) {
        this.productRepository = productRepository;
        this.reviewRepository = reviewRepository;
        this.orderItemRepository = orderItemRepository;
    }

    // Save Product - evicts the cached listing so a new/changed product shows
    // up immediately instead of customers seeing a stale catalog.
    @CacheEvict(value = {"products", "brands", "newArrivals", "categoryProducts"}, allEntries = true)
    public Product saveProduct(Product product) {
        return productRepository.save(product);
    }

    // Get All Products - CUSTOMER-FACING, active only. This was returning
    // deactivated products too before - deactivating a product did nothing
    // to actually hide it from the public browsing endpoint.
    @Cacheable("products")
    public List<Product> getAllProducts() {
        return productRepository.findAll().stream()
                .filter(p -> Boolean.TRUE.equals(p.getActive()))
                .toList();
    }

    /** Admin management view - includes inactive/deactivated products too, unlike the customer-facing list above. */
    public List<Product> getAllForAdmin() {
        return productRepository.findAll();
    }

    // Kept for backward compatibility with any existing caller of the old,
    // unranked, unpaginated search.
    public List<Product> search(String keyword) {
        return productRepository.findByNameContainingIgnoreCase(keyword);
    }

    /** Instant, typo-tolerant, relevance-ranked search - see ProductRepository.searchInstant(). */
    public Page<Product> searchInstant(String keyword, Pageable pageable) {
        if (keyword == null || keyword.isBlank()) {
            throw new BadRequestException("Search keyword is required");
        }
        return productRepository.searchInstant(keyword.trim(), pageable);
    }

    /** Category browsing - the other half of product discovery alongside search. */
    @Cacheable("categoryProducts")
    public Page<Product> browseByCategory(Long categoryId, Pageable pageable) {
        return productRepository.findByCategoryIdAndActiveTrue(categoryId, pageable);
    }

    /**
     * Sort/filter/search version of category browsing - same options and
     * same reasoning as "Shop by Brand" (see browseByBrand's doc comment
     * for why sorting happens in Java rather than SQL). Reuses the exact
     * same filterSortAndPaginate logic so the two features can never
     * silently drift apart in behavior.
     */
    public Map<String, Object> browseByCategoryFiltered(
            Long categoryId, String sort, boolean inStockOnly, String keyword, int page, int size) {

        List<Product> products = productRepository.findByCategoryIdAndActiveTrue(categoryId);
        return filterSortAndPaginate(products, sort, inStockOnly, keyword, page, size);
    }

    /** Real "New Arrivals" - sorted by actual creation time, not fabricated. */
    @Cacheable("newArrivals")
    public Page<Product> getNewArrivals(Pageable pageable) {
        return productRepository.findByActiveTrueOrderByCreatedAtDesc(pageable);
    }

    /** Only brands with at least one active product - guaranteed by the underlying GROUP BY query. */
    @Cacheable("brands")
    public List<com.gpstore.dto.response.BrandSummary> getBrandsWithCounts() {
        return productRepository.findBrandsWithProductCounts().stream()
                .map(row -> new com.gpstore.dto.response.BrandSummary((String) row[0], (Long) row[1]))
                .toList();
    }

    /**
     * "Shop by Brand" product browsing - sorted, filtered, searched, and
     * paginated. Sorting is done in Java after fetching the whole brand's
     * active product set (see ProductRepository.findByBrandIgnoreCaseAndActiveTrue's
     * doc comment for why that's a reasonable choice at this store's scale),
     * because two of the sort options (Best Selling, Highest Rated) need
     * values computed from entirely separate tables (OrderItem, Review) -
     * there's no single SQL ORDER BY that covers all eight options cleanly.
     */
    public Map<String, Object> browseByBrand(
            String brand, String sort, boolean inStockOnly, String keyword, int page, int size) {

        List<Product> products = productRepository.findByBrandIgnoreCaseAndActiveTrue(brand);
        return filterSortAndPaginate(products, sort, inStockOnly, keyword, page, size);
    }

    /**
     * Shared by "Shop by Brand" and the filtered category browsing - same
     * eight sort options, same in-stock/search filtering, same in-memory
     * pagination. Kept as one method specifically so the two features
     * can't silently diverge in behavior over time.
     */
    private Map<String, Object> filterSortAndPaginate(
            List<Product> products, String sort, boolean inStockOnly, String keyword, int page, int size) {

        if (keyword != null && !keyword.isBlank()) {
            String lowerKeyword = keyword.toLowerCase();
            products = products.stream()
                    .filter(p -> p.getName() != null && p.getName().toLowerCase().contains(lowerKeyword))
                    .toList();
        }

        if (inStockOnly) {
            products = products.stream()
                    .filter(p -> p.getVariants() != null && p.getVariants().stream()
                            .anyMatch(v -> Boolean.TRUE.equals(v.getAvailable())))
                    .toList();
        }

        products = sortProducts(products, sort);

        int totalElements = products.size();
        int totalPages = (int) Math.ceil(totalElements / (double) size);
        int fromIndex = Math.min(page * size, totalElements);
        int toIndex = Math.min(fromIndex + size, totalElements);
        List<Product> pageContent = products.subList(fromIndex, toIndex);

        Map<String, Object> response = new HashMap<>();
        response.put("content", pageContent);
        response.put("totalElements", totalElements);
        response.put("totalPages", totalPages);
        response.put("number", page);
        response.put("size", size);
        return response;
    }

    private List<Product> sortProducts(List<Product> products, String sort) {
        if (sort == null) return products;

        Comparator<Product> comparator = switch (sort.toUpperCase()) {
            case "PRICE_LOW_HIGH" -> Comparator.comparing(this::cheapestPrice);
            case "PRICE_HIGH_LOW" -> Comparator.comparing(this::cheapestPrice, Comparator.reverseOrder());
            case "NAME_ASC" -> Comparator.comparing(p -> p.getName() == null ? "" : p.getName().toLowerCase());
            case "NAME_DESC" -> Comparator.comparing(
                    (Product p) -> p.getName() == null ? "" : p.getName().toLowerCase()).reversed();
            case "NEWEST" -> Comparator.comparing(
                    (Product p) -> p.getCreatedAt() == null ? java.time.LocalDateTime.MIN : p.getCreatedAt())
                    .reversed();
            case "DISCOUNT" -> Comparator.comparing(this::discountPercent, Comparator.reverseOrder());
            case "BEST_SELLING" -> {
                Map<Long, Long> unitsSold = new HashMap<>();
                for (Object[] row : orderItemRepository.findTotalUnitsSoldByProduct()) {
                    unitsSold.put((Long) row[0], (Long) row[1]);
                }
                yield Comparator.comparing(
                        (Product p) -> unitsSold.getOrDefault(p.getId(), 0L), Comparator.reverseOrder());
            }
            case "HIGHEST_RATED" -> {
                Map<Long, Double> ratings = new HashMap<>();
                for (Object[] row : reviewRepository.findAverageRatingsByProduct()) {
                    ratings.put((Long) row[0], (Double) row[1]);
                }
                yield Comparator.comparing(
                        (Product p) -> ratings.getOrDefault(p.getId(), 0.0), Comparator.reverseOrder());
            }
            default -> null;
        };

        if (comparator == null) return products;
        return products.stream().sorted(comparator).toList();
    }

    private java.math.BigDecimal cheapestPrice(Product product) {
        if (product.getVariants() == null || product.getVariants().isEmpty()) {
            return java.math.BigDecimal.ZERO;
        }
        return product.getVariants().stream()
                .map(ProductVariant::getSellingPrice)
                .filter(java.util.Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(java.math.BigDecimal.ZERO);
    }

    private java.math.BigDecimal discountPercent(Product product) {
        if (product.getVariants() == null || product.getVariants().isEmpty()) {
            return java.math.BigDecimal.ZERO;
        }
        return product.getVariants().stream()
                .filter(v -> v.getMrp() != null && v.getSellingPrice() != null
                        && v.getMrp().compareTo(java.math.BigDecimal.ZERO) > 0
                        && v.getMrp().compareTo(v.getSellingPrice()) > 0)
                .map(v -> v.getMrp().subtract(v.getSellingPrice())
                        .divide(v.getMrp(), 4, java.math.RoundingMode.HALF_UP))
                .max(Comparator.naturalOrder())
                .orElse(java.math.BigDecimal.ZERO);
    }

    public Product getProductById(Long id) {
        return productRepository.findById(id).orElse(null);
    }

    public Product getByIdOrThrow(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new com.gpstore.exception.ResourceNotFoundException("Product not found"));
    }

    /** Didn't exist before - a product could be created but never edited afterward. */
    @CacheEvict(value = {"products", "brands", "newArrivals", "categoryProducts"}, allEntries = true)
    public Product update(Long id, Product updated) {
        Product existing = getByIdOrThrow(id);

        existing.setName(updated.getName());
        existing.setBrand(updated.getBrand());
        existing.setCategory(updated.getCategory());
        existing.setActive(updated.getActive());

        return productRepository.save(existing);
    }

    /**
     * Soft-delete only - same reasoning as Category: a hard delete would
     * orphan every variant/order-item/cart-item still referencing this
     * product, or fail on the FK constraint. Deactivating just stops it
     * showing up to customers.
     */
    @CacheEvict(value = {"products", "brands", "newArrivals", "categoryProducts"}, allEntries = true)
    public void deactivate(Long id) {
        Product product = getByIdOrThrow(id);
        product.setActive(false);
        productRepository.save(product);
    }
}