package com.gpstore.service;

import com.gpstore.entity.Product;
import com.gpstore.exception.BadRequestException;
import com.gpstore.repository.ProductRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    // Save Product - evicts the cached listing so a new/changed product shows
    // up immediately instead of customers seeing a stale catalog.
    @CacheEvict(value = "products", allEntries = true)
    public Product saveProduct(Product product) {
        return productRepository.save(product);
    }

    // Get All Products - this is exactly the "read-heavy, rarely-changing"
    // endpoint caching is meant for. Evicted automatically on any product write.
    @Cacheable("products")
    public List<Product> getAllProducts() {
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
    public Page<Product> browseByCategory(Long categoryId, Pageable pageable) {
        return productRepository.findByCategoryIdAndActiveTrue(categoryId, pageable);
    }

    public Product getProductById(Long id) {
        return productRepository.findById(id).orElse(null);
    }
}