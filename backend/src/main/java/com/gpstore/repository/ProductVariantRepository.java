package com.gpstore.repository;

import com.gpstore.entity.ProductVariant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ProductVariantRepository
        extends JpaRepository<ProductVariant, Long> {

    /**
     * The catalog seeder's idempotency lookup - see CatalogSeedService.
     *
     * Optional rather than a list because V15 puts a unique index on this
     * column (partial, since every pre-existing variant has a null SKU). If
     * two rows ever share a SKU the database has already been corrupted and
     * an exception here is the correct outcome, not a silently-picked first
     * row.
     */
    Optional<ProductVariant> findBySku(String sku);

    /**
     * Variants of seeded products, fetched with their product so the image
     * backfill does not trigger a lazy load per row.
     */
    @Query("""
           select v from ProductVariant v
           join fetch v.product p
           where p.isTestData = true
           order by v.id
           """)
    List<ProductVariant> findSeededVariants();
}
