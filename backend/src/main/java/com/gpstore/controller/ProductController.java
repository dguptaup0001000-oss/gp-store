package com.gpstore.controller;

import com.gpstore.entity.Product;
import com.gpstore.service.ProductService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    // Create Product
    @PostMapping
    public Product createProduct(@RequestBody Product product) {
        return productService.saveProduct(product);
    }

    // Get All Products
    @GetMapping
    public List<Product> getAllProducts() {
        return productService.getAllProducts();
    }

    // Kept for backward compatibility with any existing client already
    // wired to this exact shape (unranked, unpaginated).
    @GetMapping("/search")
    public List<Product> searchProducts(@RequestParam String keyword) {
        return productService.search(keyword);
    }

    // Instant search: typo-tolerant, ranked by relevance, paginated.
    // This is the one a real search-as-you-type UI should call.
    @GetMapping("/search/instant")
    public Page<Product> searchInstant(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        return productService.searchInstant(keyword, pageable);
    }

    // Category browsing - paginated, only active products.
    @GetMapping("/category/{categoryId}")
    public Page<Product> browseByCategory(
            @PathVariable Long categoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        return productService.browseByCategory(categoryId, pageable);
    }

    @GetMapping("/{id}")
    public Product getProduct(@PathVariable Long id) {
        return productService.getProductById(id);
    }
}
