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

    // ------------------------------------------------------------------
    // Variant galleries (V22)
    //
    // A row with a variant belongs to that variant; a row without belongs to
    // the product, as every row written before V22 does. The two queries
    // below never see each other's rows, which is what lets both kinds live
    // in one table without either changing meaning.
    // ------------------------------------------------------------------

    /** One variant's photos, in display order. The first is the primary. */
    List<ProductImage> findByProductVariantIdOrderBySortOrderAsc(Long productVariantId);

    /**
     * Photos for every variant of one product, in ONE query.
     *
     * The detail screen shows a product and all its variants, so asking per
     * variant would be an N+1 on exactly the screen this feature exists for.
     * Ordered by variant then sort_order so the caller can group in a single
     * pass without re-sorting.
     */
    @Query("SELECT pi FROM ProductImage pi "
            + "WHERE pi.productVariant.id IN :variantIds "
            + "ORDER BY pi.productVariant.id, pi.sortOrder")
    List<ProductImage> findByProductVariantIdIn(@Param("variantIds") List<Long> variantIds);

    void deleteByProductVariantId(Long productVariantId);

    long countByProductVariantId(Long productVariantId);
}
