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

    /**
     * Variants that have no real photograph yet - the backfill's real
     * worklist.
     *
     * <p>WHY THIS IS NOT "isTestData = true". That was the original
     * criterion, and it silently excluded the products that need this most.
     * A shop's live catalogue carries isTestData = false, so a product
     * showing a stand-in image was never even CONSIDERED by the backfill: it
     * reported considered=0 and looked like a clean run.
     *
     * <p>A PLACEHOLDER IS NOT AN IMAGE. A URL pointing at a text-rendering
     * service resolves, returns 200, and draws the product's own name on a
     * coloured square - so every check that asks "is there a URL" says yes
     * while the customer looks at a picture of some words. Those hosts are
     * matched explicitly here so such a variant counts as needing an image
     * rather than having one.
     *
     * <p>Ordered by id so a bounded run is resumable: the next run starts
     * where the last one stopped, because whatever it filled no longer
     * matches.
     */
    @Query("""
           select v from ProductVariant v
           join fetch v.product p
           where p.active = true
             and (v.imageUrl is null
                  or v.imageUrl = ''
                  or lower(v.imageUrl) like '%placehold.co%'
                  or lower(v.imageUrl) like '%placeholder.com%'
                  or lower(v.imageUrl) like '%dummyimage.com%'
                  or lower(v.imageUrl) like '%fakeimg.pl%')
           order by v.id
           """)
    List<ProductVariant> findVariantsWithoutRealImages();
}
