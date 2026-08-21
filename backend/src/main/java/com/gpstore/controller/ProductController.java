package com.gpstore.controller;

import com.gpstore.entity.Product;
import com.gpstore.service.ProductService;
import com.gpstore.dto.response.ProductResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;
    private final com.gpstore.search.SmartSearchService smartSearchService;

    public ProductController(ProductService productService,
                             com.gpstore.search.SmartSearchService smartSearchService) {
        this.smartSearchService = smartSearchService;
        this.productService = productService;
    }

    // Create Product
    @PostMapping
    public ProductResponse createProduct(@RequestBody Product product) {
        return productService.saveProduct(product);
    }

    // Get All Products
    @GetMapping
    public List<ProductResponse> getAllProducts() {
        return productService.getAllProducts();
    }

    // Admin only (enforced in SecurityConfig, must come before the broad
    // public GET rule) - includes deactivated products too, unlike the
    // customer-facing list above.
    @GetMapping("/admin/all")
    public List<ProductResponse> getAllForAdmin() {
        return productService.getAllForAdmin();
    }

    // Kept for backward compatibility with any existing client already
    // wired to this exact shape (unranked, unpaginated).
    @GetMapping("/search")
    public List<ProductResponse> searchProducts(@RequestParam String keyword) {
        return productService.search(keyword);
    }

    // Instant search: typo-tolerant, ranked by relevance, paginated.
    // This is the one a real search-as-you-type UI should call.
    @GetMapping("/search/instant")
    public Page<ProductResponse> searchInstant(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        return productService.searchInstant(keyword, pageable);
    }

    /**
     * Endless home feed - every active product, one page at a time.
     *
     * Separate from the plain GET /api/products above rather than changing
     * its shape, so nothing already calling that breaks. That endpoint stays
     * a capped, unpaginated convenience list; this one is what the home
     * screen scrolls through.
     *
     * size is capped at 50 for the same reason as every other paginated
     * endpoint here: the page size is client-supplied, and without a cap a
     * single request could ask for the entire catalogue and undo the point
     * of paginating.
     */
    @GetMapping("/feed")
    public Page<ProductResponse> feed(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        // Sort lives here, not in the client's hands: a client-chosen sort
        // would let one caller destabilise its own pagination.
        Pageable pageable = PageRequest.of(page, Math.min(size, 50), Sort.by(Sort.Direction.ASC, "id"));
        return productService.browseAll(pageable);
    }

    // Category browsing - paginated, only active products.
    @GetMapping("/category/{categoryId}")
    public Page<ProductResponse> browseByCategory(
            @PathVariable Long categoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        return productService.browseByCategory(categoryId, pageable);
    }

    /**
     * Smart Search: typo-tolerant, Hinglish-aware, and it says what it
     * understood.
     *
     * <p>Additive - /search and /search/instant above are unchanged, so
     * nothing already calling them is affected.
     */
    @GetMapping("/search/smart")
    public Map<String, Object> searchSmart(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        com.gpstore.search.SmartSearchResult result = smartSearchService.search(keyword, pageable);

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("query", result.getQuery());
        // Nulls are included rather than omitted so the client can rely on
        // the keys existing and does not have to distinguish "absent" from
        // "no correction".
        body.put("interpretedAs", result.getInterpretedAs());
        body.put("didYouMean", result.getDidYouMean());
        body.put("content", result.getResults().getContent());
        body.put("totalElements", result.getResults().getTotalElements());
        body.put("totalPages", result.getResults().getTotalPages());
        body.put("number", result.getResults().getNumber());
        return body;
    }

    // Sort/filter/search version - same 8 sort options as Shop by Brand:
    // PRICE_LOW_HIGH, PRICE_HIGH_LOW, NAME_ASC, NAME_DESC, NEWEST, DISCOUNT,
    // BEST_SELLING, HIGHEST_RATED. Kept as a separate endpoint from the
    // plain one above rather than changing its shape, so nothing already
    // calling the plain version breaks.
    @GetMapping("/category/{categoryId}/filtered")
    public Map<String, Object> browseByCategoryFiltered(
            @PathVariable Long categoryId,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "false") boolean inStockOnly,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return productService.browseByCategoryFiltered(categoryId, sort, inStockOnly, keyword, page, Math.min(size, 50));
    }

    /**
     * The home screen's Bestsellers collage, whole, in one request.
     *
     * This endpoint exists to delete five HTTP requests from every cold app
     * open: the collage previously called /category/{id} once per tile. The
     * defaults are what the UI actually draws - six tiles of four - rather
     * than a page size chosen for a different screen, and both are clamped
     * server-side so this cannot be widened into a catalogue dump from the
     * query string.
     */
    @GetMapping("/bestsellers")
    public List<com.gpstore.dto.response.BestsellerTileResponse> getBestsellers(
            @RequestParam(defaultValue = "6") int categories,
            @RequestParam(defaultValue = "4") int perCategory) {

        return productService.getBestsellerTiles(categories, perCategory);
    }

    // Real "New Arrivals" - sorted by actual product creation time.
    @GetMapping("/new-arrivals")
    public Page<ProductResponse> getNewArrivals(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        return productService.getNewArrivals(pageable);
    }

    // "Shop by Brand" - only brands that actually have products right now.
    @GetMapping("/brands")
    public List<com.gpstore.dto.response.BrandSummary> getBrands() {
        return productService.getBrandsWithCounts();
    }

    // Sort options: PRICE_LOW_HIGH, PRICE_HIGH_LOW, NAME_ASC, NAME_DESC,
    // NEWEST, DISCOUNT, BEST_SELLING, HIGHEST_RATED.
    @GetMapping("/brand/{brand}")
    public Map<String, Object> browseByBrand(
            @PathVariable String brand,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "false") boolean inStockOnly,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return productService.browseByBrand(brand, sort, inStockOnly, keyword, page, Math.min(size, 50));
    }

    @GetMapping("/{id}")
    public ProductResponse getProduct(@PathVariable Long id) {
        return productService.getProductById(id);
    }

    // Admin only (enforced in SecurityConfig) - didn't exist before, a
    // product could be created but never edited.
    @PutMapping("/{id}")
    public ProductResponse updateProduct(@PathVariable Long id, @RequestBody Product product) {
        return productService.update(id, product);
    }

    // Admin only - soft delete (deactivate), same reasoning as Category.
    @DeleteMapping("/{id}")
    public String deactivateProduct(@PathVariable Long id) {
        productService.deactivate(id);
        return "Product deactivated";
    }
}
