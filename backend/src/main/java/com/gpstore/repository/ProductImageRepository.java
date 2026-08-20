package com.gpstore.repository;

import com.gpstore.entity.ProductImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {

    /** One product's gallery, in display order. */
    List<ProductImage> findByProductIdOrderBySortOrderAsc(Long productId);

    /**
     * Galleries for many products in ONE query.
     *
     * Exists so a list endpoint can attach images without asking per
     * product - the exact N+1 that ProductService.batchFetchWithVariants
     * already avoids for variants. Nothing calls this from a LIST path
     * today (listings deliberately show one variant thumbnail, not a
     * gallery), but any future "show 2 images per card" idea must go
     * through here rather than looping.
     */
    @Query("SELECT pi FROM ProductImage pi WHERE pi.product.id IN :productIds ORDER BY pi.product.id, pi.sortOrder")
    List<ProductImage> findByProductIdIn(@Param("productIds") List<Long> productIds);

    void deleteByProductId(Long productId);

    long countByProductId(Long productId);
}
