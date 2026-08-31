package com.gpstore.repository;

import com.gpstore.entity.ProductVariant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * One variant with its product already loaded.
     *
     * FOR THE CART PATH, where the difference is measurable rather than
     * cosmetic. Every add-to-cart checks the product's active flag, which is
     * one association hop from the variant - so a plain findById costs a
     * second round trip, and that round trip happens INSIDE the customer row
     * lock that every other cart request for the same customer is queued
     * behind. Fetching both together removes a network round trip from a
     * critical section, which is worth far more than the one query it saves.
     */
    @Query("select v from ProductVariant v join fetch v.product where v.id = :id")
    Optional<ProductVariant> findByIdWithProduct(@Param("id") Long id);

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

    /**
     * One listing thumbnail per product, for the admin's top-products list.
     *
     * <p>Returns every candidate rather than one row per product on purpose.
     * Picking "the" thumbnail in SQL needs a window function or a correlated
     * subquery, and neither is worth it for a list capped at 50 products; the
     * caller keeps the FIRST row it sees per product, and the ordering below
     * makes that deterministic (lowest variant id wins, which is the same
     * variant the catalogue itself shows first).
     *
     * <p>Blank strings are excluded alongside nulls because the seeder wrote
     * empty imageUrl on some rows, and an empty string would beat a real
     * photograph on a later variant.
     */
    @Query("select v.product.id, v.imageUrl "
            + "from ProductVariant v "
            + "where v.product.id in :productIds "
            + "and v.imageUrl is not null and v.imageUrl <> '' "
            + "order by v.product.id, v.id")
    List<Object[]> findThumbnailCandidates(@Param("productIds") List<Long> productIds);
}
