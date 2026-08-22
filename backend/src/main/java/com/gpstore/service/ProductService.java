package com.gpstore.service;

import com.gpstore.entity.Product;
import com.gpstore.exception.BadRequestException;
import com.gpstore.repository.ProductBrowseRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.dto.response.BestsellerTileResponse;
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
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final com.gpstore.repository.ProductImageRepository productImageRepository;
    private final ProductBrowseRepository productBrowseRepository;

    public ProductService(
            ProductRepository productRepository,
            ProductBrowseRepository productBrowseRepository,
            com.gpstore.repository.ProductImageRepository productImageRepository) {
        this.productRepository = productRepository;
        this.productImageRepository = productImageRepository;
        this.productBrowseRepository = productBrowseRepository;
    }

    // Save Product - evicts the cached listing so a new/changed product shows
    // up immediately instead of customers seeing a stale catalog.
    @CacheEvict(value = {"products", "brands", "newArrivals", "categoryProducts", "productDetail", "productSearch", "productFeed", "bestsellerTiles", "trending", "frequentlyBought"}, allEntries = true)
    @Transactional
    public ProductResponse saveProduct(Product product) {
        return ProductResponse.from(productRepository.save(product));
    }

    // A public, unauthenticated GET endpoint backs each of the three methods
    // below (see ProductController) - none of them may ever run an unbounded
    // findAll()/findByX() again. Real clients use the paginated/ranked
    // /api/products/search/instant endpoint; these three exist only for
    // backward compatibility with old callers that expect a bare JSON array,
    // capped at a safe size instead of the whole catalog.
    private static final int LEGACY_UNPAGINATED_CAP = 100;

    // Get All Products - CUSTOMER-FACING, active only. This was returning
    // deactivated products too before - deactivating a product did nothing
    // to actually hide it from the public browsing endpoint. Was also
    // previously a full unbounded findAll() - on a public endpoint, that's a
    // full-catalog-dump-on-every-request risk that only gets worse as the
    // catalog grows, regardless of concurrent user count.
    @Transactional(readOnly = true)
    @Cacheable("products")
    public List<ProductResponse> getAllProducts() {
        return productRepository
                .findByActiveTrueOrderByCreatedAtDesc(org.springframework.data.domain.PageRequest.of(0, LEGACY_UNPAGINATED_CAP))
                .map(ProductResponse::from)
                .toList();
    }

    /** Admin management view - includes inactive/deactivated products too, unlike the customer-facing list above. */
    @Transactional(readOnly = true)
    public List<ProductResponse> getAllForAdmin() {
        return productRepository
                .findAllByOrderByCreatedAtDesc(org.springframework.data.domain.PageRequest.of(0, LEGACY_UNPAGINATED_CAP))
                .map(ProductResponse::from)
                .toList();
    }

    // Kept for backward compatibility with any existing caller of the old,
    // unranked, unpaginated search.
    @Transactional(readOnly = true)
    public List<ProductResponse> search(String keyword) {
        return productRepository
                .findByNameContainingIgnoreCase(keyword, org.springframework.data.domain.PageRequest.of(0, LEGACY_UNPAGINATED_CAP))
                .stream()
                .map(ProductResponse::from)
                .toList();
    }

    /**
     * Instant, typo-tolerant, relevance-ranked search - see
     * ProductRepository.searchInstant(). That native query returns bare
     * Product entities with nothing eager-fetched (same situation
     * batchFetchWithVariants exists for), so this used to lazy-load each
     * result's category and variants one product at a time while mapping to
     * ProductResponse - confirmed via isolated timing as the actual cause of
     * a multi-second response that was identical regardless of keyword
     * (same page size, same ~40 extra sequential round trips either way).
     * Batching the re-fetch the same way browseByCategory/getNewArrivals
     * already do fixes it in one extra round trip instead.
     */
    // Cached (keyed on keyword+page, Spring's default composite key) because
    // grocery search terms repeat heavily across different customers (many
    // different people search "rice", "milk", "oil" on any given day) - a
    // cache hit skips the database round trips entirely, not just the N+1
    // this method already avoids.
    @Transactional(readOnly = true)
    @Cacheable("productSearch")
    public Page<ProductResponse> searchInstant(String keyword, Pageable pageable) {
        if (keyword == null || keyword.isBlank()) {
            throw new BadRequestException("Search keyword is required");
        }
        return batchFetchWithVariants(productRepository.searchInstant(keyword.trim(), pageable));
    }

    /**
     * The endless home feed: every active product, page by page.
     *
     * This is what lets the home screen keep going past New Arrivals instead
     * of ending after three carousels. It is a genuine server-side page - the
     * client asks for page N and gets 20 products plus whether more exist -
     * so a catalogue of thousands is never loaded into memory at once, on
     * either side.
     *
     * SORTED BY ID ASCENDING, and that matters more than it looks. Infinite
     * scroll re-queries with an offset, so the sort has to be stable: with
     * createdAt DESC, a product added mid-scroll shifts every later page and
     * the customer sees a duplicate or skips an item. New ids land at the
     * end, leaving already-fetched pages meaning exactly what they meant.
     *
     * Cached like the other browse paths. The cache key includes the
     * Pageable, so each page is cached independently rather than the whole
     * catalogue under one key.
     */
    @Transactional(readOnly = true)
    @Cacheable("productFeed")
    public Page<ProductResponse> browseAll(Pageable pageable) {
        return batchFetchWithVariants(productRepository.findByActiveTrue(pageable));
    }

    /**
     * The whole Bestsellers collage in one call.
     *
     * REPLACES SIX REQUESTS. The app was calling browseByCategory once per
     * category tile on every cold home open - six HTTP round trips, six auth
     * filter chains, six connection acquisitions - to render twenty-four
     * thumbnails. This is one request backed by one SQL statement, and it
     * stays one of each however many tiles the collage grows to.
     *
     * Both limits are bounded by the caller and clamped here, so a crafted
     * query string cannot turn the collage endpoint into a full catalogue
     * dump. perCategory defaults to what the UI actually draws - four - not
     * to a page size of twenty.
     *
     * Cached. The collage changes only when the catalogue does, and it is
     * requested by every customer who opens the app.
     */
    @Transactional(readOnly = true)
    @Cacheable("bestsellerTiles")
    public List<BestsellerTileResponse> getBestsellerTiles(int categoryLimit, int perCategory) {
        int categories = clamp(categoryLimit, 1, MAX_BESTSELLER_CATEGORIES);
        int products = clamp(perCategory, 1, MAX_BESTSELLER_PRODUCTS_PER_CATEGORY);

        // LinkedHashMap: the query already returns rows grouped and ordered
        // by category, and the collage should render them in that order
        // rather than whatever a HashMap happens to iterate in.
        Map<Long, BestsellerTileResponse> tiles = new LinkedHashMap<>();
        for (ProductBrowseRepository.BestsellerRow row :
                productBrowseRepository.findBestsellerTiles(null, categories, products)) {
            BestsellerTileResponse tile = tiles.computeIfAbsent(
                    row.categoryId(),
                    id -> new BestsellerTileResponse(
                            id, row.categoryName(), new ArrayList<>(), new ArrayList<>(),
                            // Identical on every row of this category - taking
                            // it from the first is not a shortcut, it is the
                            // only row that creates the tile.
                            row.categoryTotal()));
            tile.getProductIds().add(row.productId());
            // Added even when null - see BestsellerTileResponse.imageUrls for
            // why the slot is kept rather than skipped.
            tile.getImageUrls().add(row.imageUrl());
        }
        return new ArrayList<>(tiles.values());
    }

    /** The collage is six tiles of four; these are the ceilings, not the defaults. */
    private static final int MAX_BESTSELLER_CATEGORIES = 12;
    private static final int MAX_BESTSELLER_PRODUCTS_PER_CATEGORY = 8;

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
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

    // Product detail is the single most-tapped customer-facing endpoint (every
    // "view product" hits it) - plain findById() left category/variants lazy,
    // so ProductResponse.from() cost 2 extra round trips beyond the initial
    // fetch (3 total for what should be 1), on every single view, uncached.
    // findByIdIn already eager-fetches both via @EntityGraph, so reusing it
    // with a single-element list gets the same result in one query, and
    // caching this (product data barely changes) means most views don't hit
    // the database at all.
    @Transactional(readOnly = true)
    @Cacheable("productDetail")
    public ProductResponse getProductById(Long id) {
        Product entity = productRepository.findByIdIn(List.of(id)).stream()
                .findFirst()
                .orElse(null);

        if (entity == null) {
            return null;
        }

        ProductResponse product = ProductResponse.from(entity);

        // The 3D model, like the gallery below, is attached ONLY here.
        // ProductResponse.from deliberately leaves it null so that no list
        // response ever carries it - the field exists for one screen and
        // should cost nothing on every other one.
        if (entity.getModel3dUrl() != null && !entity.getModel3dUrl().isBlank()) {
            product = product.withModel3dUrl(entity.getModel3dUrl());
        }

        // The gallery is attached HERE and nowhere else, on purpose.
        //
        // Detail is the only screen that shows more than one image, so this
        // is the only place worth the extra query. Attaching galleries to
        // list responses would mean fetching up to five URLs for every card
        // in a 20-product grid to render one thumbnail - bandwidth and
        // serialization the user never sees, on every browse request, which
        // is exactly the traffic that already saturates this instance.
        //
        // Listings continue to use ProductVariant.imageUrl, unchanged.
        //
        // One extra query per detail view, and it is cached with the rest of
        // the response under "productDetail".
        List<String> gallery = productImageRepository.findByProductIdOrderBySortOrderAsc(id).stream()
                .map(com.gpstore.entity.ProductImage::getImageUrl)
                .filter(url -> url != null && !url.isBlank())
                .toList();

        // No gallery rows means an existing product that predates this
        // feature: return it exactly as before and let the client fall back
        // to the variant thumbnail, rather than handing back an empty
        // gallery the UI might render as a broken strip.
        return gallery.isEmpty() ? product : product.withImages(gallery);
    }

    public Product getByIdOrThrow(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new com.gpstore.exception.ResourceNotFoundException("Product not found"));
    }

    /** Didn't exist before - a product could be created but never edited afterward. */
    @CacheEvict(value = {"products", "brands", "newArrivals", "categoryProducts", "productDetail", "productSearch", "productFeed", "bestsellerTiles", "trending", "frequentlyBought"}, allEntries = true)
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
    @CacheEvict(value = {"products", "brands", "newArrivals", "categoryProducts", "productDetail", "productSearch", "productFeed", "bestsellerTiles", "trending", "frequentlyBought"}, allEntries = true)
    public void deactivate(Long id) {
        Product product = getByIdOrThrow(id);
        product.setActive(false);
        productRepository.save(product);
    }
}