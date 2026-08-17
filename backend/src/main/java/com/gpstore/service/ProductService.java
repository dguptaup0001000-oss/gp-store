package com.gpstore.service;

import com.gpstore.entity.Product;
import com.gpstore.exception.BadRequestException;
import com.gpstore.repository.ProductBrowseRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.dto.response.ProductResponse;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductBrowseRepository productBrowseRepository;

    public ProductService(
            ProductRepository productRepository,
            ProductBrowseRepository productBrowseRepository) {
        this.productRepository = productRepository;
        this.productBrowseRepository = productBrowseRepository;
    }

    // Save Product - evicts the cached listing so a new/changed product shows
    // up immediately instead of customers seeing a stale catalog.
    @CacheEvict(value = {"products", "brands", "newArrivals", "categoryProducts"}, allEntries = true)
    @Transactional
    public ProductResponse saveProduct(Product product) {
        return ProductResponse.from(productRepository.save(product));
    }

    // Get All Products - CUSTOMER-FACING, active only. This was returning
    // deactivated products too before - deactivating a product did nothing
    // to actually hide it from the public browsing endpoint.
    @Transactional(readOnly = true)
    @Cacheable("products")
    public List<ProductResponse> getAllProducts() {
        return productRepository.findAll().stream()
                .filter(p -> Boolean.TRUE.equals(p.getActive()))
                .map(ProductResponse::from)
                .toList();
    }

    /** Admin management view - includes inactive/deactivated products too, unlike the customer-facing list above. */
    @Transactional(readOnly = true)
    public List<ProductResponse> getAllForAdmin() {
        return productRepository.findAll().stream()
                .map(ProductResponse::from)
                .toList();
    }

    // Kept for backward compatibility with any existing caller of the old,
    // unranked, unpaginated search.
    @Transactional(readOnly = true)
    public List<ProductResponse> search(String keyword) {
        return productRepository.findByNameContainingIgnoreCase(keyword).stream()
                .map(ProductResponse::from)
                .toList();
    }

    /** Instant, typo-tolerant, relevance-ranked search - see ProductRepository.searchInstant(). */
    @Transactional(readOnly = true)
    public Page<ProductResponse> searchInstant(String keyword, Pageable pageable) {
        if (keyword == null || keyword.isBlank()) {
            throw new BadRequestException("Search keyword is required");
        }
        return productRepository.searchInstant(keyword.trim(), pageable).map(ProductResponse::from);
    }

    /** Category browsing - the other half of product discovery alongside search. */
    @Transactional(readOnly = true)
    @Cacheable("categoryProducts")
    public Page<ProductResponse> browseByCategory(Long categoryId, Pageable pageable) {
        return batchFetchWithVariants(productRepository.findByCategoryIdAndActiveTrue(categoryId, pageable));
    }

    /**
     * Sort/filter/search version of category browsing - same options and
     * same reasoning as "Shop by Brand" (see browseByBrand's doc comment).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> browseByCategoryFiltered(
            Long categoryId, String sort, boolean inStockOnly, String keyword, int page, int size) {

        ProductBrowseRepository.BrowseResult result =
                productBrowseRepository.browse(null, categoryId, sort, inStockOnly, keyword, page, size);
        return toBrowseResponse(result, page, size);
    }

    /** Real "New Arrivals" - sorted by actual creation time, not fabricated. */
    @Transactional(readOnly = true)
    @Cacheable("newArrivals")
    public Page<ProductResponse> getNewArrivals(Pageable pageable) {
        return batchFetchWithVariants(productRepository.findByActiveTrueOrderByCreatedAtDesc(pageable));
    }

    /**
     * Turns a Page<Product> (eager-fetched category only, per
     * ProductRepository's @EntityGraph comments - NOT variants, to avoid
     * Hibernate's collection-fetch+pagination trap) into a Page<ProductResponse>
     * with variants populated too, without the N+1 that ProductResponse.from()
     * would otherwise trigger by lazy-loading each product's variants one at
     * a time. Same batching trick as RecommendationService.fetchInRankedOrder:
     * one extra query for every product ID on this page at once (via
     * findByIdIn's @EntityGraph({"category","variants"})), instead of one
     * lazy-load query per product - for a 20-item page, 20 sequential round
     * trips instead of 1, which was slow enough to blow past the app's
     * request timeout on every attempt (so this endpoint's own @Cacheable
     * never even got a chance to populate the cache).
     *
     * findByIdIn doesn't preserve input order, so results are re-sorted back
     * into the original page's order (whatever sort the caller asked for)
     * before re-wrapping as a Page, preserving the original totalElements/
     * totalPages metadata that came from the real paginated query.
     */
    private Page<ProductResponse> batchFetchWithVariants(Page<Product> page) {
        List<Long> orderedIds = page.getContent().stream().map(Product::getId).toList();
        return new PageImpl<>(batchToResponseList(orderedIds), page.getPageable(), page.getTotalElements());
    }

    /**
     * Same batching trick, keyed by ID so it works for both a Page<Product>'s
     * content (see batchFetchWithVariants) and ProductBrowseRepository's
     * plain sorted List<Product> - its native query returns bare entities
     * with no relations eager-fetched at all, so every product's category
     * AND variants would otherwise lazy-load one at a time.
     */
    private List<ProductResponse> batchToResponseList(List<Long> orderedIds) {
        if (orderedIds.isEmpty()) {
            return List.of();
        }

        Map<Long, Product> byId = new HashMap<>();
        for (Product product : productRepository.findByIdIn(orderedIds)) {
            byId.put(product.getId(), product);
        }

        List<ProductResponse> content = new ArrayList<>(orderedIds.size());
        for (Long id : orderedIds) {
            Product product = byId.get(id);
            if (product != null) {
                content.add(ProductResponse.from(product));
            }
        }
        return content;
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
     * paginated entirely in SQL (see ProductBrowseRepository's doc comment)
     * instead of loading the whole brand's product set into Java to sort -
     * including, for Best Selling/Highest Rated, no longer pulling the
     * entire order_items/reviews tables into a HashMap on every request.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> browseByBrand(
            String brand, String sort, boolean inStockOnly, String keyword, int page, int size) {

        ProductBrowseRepository.BrowseResult result =
                productBrowseRepository.browse(brand, null, sort, inStockOnly, keyword, page, size);
        return toBrowseResponse(result, page, size);
    }

    /** Shared response shape for both browseByBrand and browseByCategoryFiltered. */
    private Map<String, Object> toBrowseResponse(ProductBrowseRepository.BrowseResult result, int page, int size) {
        List<ProductResponse> content = batchToResponseList(result.products().stream().map(Product::getId).toList());
        int totalPages = (int) Math.ceil(result.totalElements() / (double) size);

        Map<String, Object> response = new HashMap<>();
        response.put("content", content);
        response.put("totalElements", result.totalElements());
        response.put("totalPages", totalPages);
        response.put("number", page);
        response.put("size", size);
        return response;
    }

    @Transactional(readOnly = true)
    public ProductResponse getProductById(Long id) {
        return ProductResponse.from(productRepository.findById(id).orElse(null));
    }

    public Product getByIdOrThrow(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new com.gpstore.exception.ResourceNotFoundException("Product not found"));
    }

    /** Didn't exist before - a product could be created but never edited afterward. */
    @CacheEvict(value = {"products", "brands", "newArrivals", "categoryProducts"}, allEntries = true)
    @Transactional
    public ProductResponse update(Long id, Product updated) {
        Product existing = getByIdOrThrow(id);

        existing.setName(updated.getName());
        existing.setBrand(updated.getBrand());
        existing.setCategory(updated.getCategory());
        existing.setActive(updated.getActive());

        return ProductResponse.from(productRepository.save(existing));
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