package com.gpstore.dto.response;

import com.gpstore.entity.Product;

import java.io.Serializable;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Explicit response shape instead of returning the raw Product entity.
 * Needed because ProductController exposed raw Product/List<Product> across
 * getAllProducts, getAllForAdmin, searchProducts, searchInstant,
 * browseByCategory, browseByCategoryFiltered, getNewArrivals, browseByBrand,
 * getProduct, createProduct, and updateProduct with no @Transactional - the
 * last entry in the EAGER->LAZY audit. Category is nested rather than
 * flattened (unlike CartItemResponse/OrderItemResponse) because the Flutter
 * Product model expects a full nested Category object, not a name/brand
 * string.
 *
 * Serializable (along with VariantResponse/CategoryResponse below) because
 * getAllProducts/browseByCategory/getNewArrivals are all @Cacheable, and
 * Spring's default RedisCacheManager uses plain JDK serialization for cached
 * values - which requires the entire object graph being cached to support
 * it. Without this, caching one of these lists successfully (as opposed to
 * failing/timing out first, which was the case for getNewArrivals before its
 * N+1 fix - see ProductService.batchFetchWithVariants) throws
 * NotSerializableException from inside the cache write, turning a
 * successful DB result into a failed request.
 */
public class ProductResponse implements Serializable {

    /**
     * Pinned so this DTO can gain fields without invalidating entries
     * already sitting in Redis.
     *
     * Without an explicit value the JVM derives one from the class
     * structure, so adding a single field changes it and every cached entry
     * written by the previous build fails to deserialise with
     * InvalidClassException on the next read. Spring's default cache error
     * handler rethrows that, which turns a routine DTO change into a 500 on
     * every browse request until the TTL drains - see CacheConfig, which
     * now also makes that survivable.
     *
     * Java's rules make ADDING a field compatible once this is pinned;
     * removing or retyping one is not, and still needs a cache flush.
     */
    private static final long serialVersionUID = 1L;

    private final Long id;
    private final String name;
    private final String brand;
    private final CategoryResponse category;
    private final List<VariantResponse> variants;
    private final Boolean active;

    /**
     * The product's gallery, in display order, or an EMPTY LIST when it has
     * none - never null, so no client has to null-check before iterating.
     *
     * Additive and backward compatible in both directions. Existing products
     * have no rows in product_images (the migration deliberately backfills
     * nothing), so they serialise as [] here and clients fall back to the
     * variant thumbnail exactly as they did before this field existed. An
     * older app build ignores the extra JSON key.
     */
    private final List<String> images;

    /**
     * Catalog metadata, all additive and all safe to be absent.
     *
     * An older app build ignores unknown JSON keys, and a newer build reading
     * an older cached entry sees null - which every one of these is written
     * to tolerate. That is also why serialVersionUID above stays 1L: Java's
     * rules make ADDING fields compatible once it is pinned, so entries
     * already sitting in Redis keep deserialising instead of throwing
     * InvalidClassException on every browse request until the TTL drains.
     */
    private final String subcategory;
    private final Boolean bestseller;
    private final Boolean featured;

    /**
     * True when the price shown is assumed test data rather than a checked
     * shelf price. Exposed rather than hidden ON PURPOSE: the shop's own
     * admin screens need to be able to see which products still need
     * verifying, and a flag that only exists in the database is a flag
     * nobody acts on.
     */
    private final Boolean testData;

    /**
     * Null on every LIST response, populated only on product detail - the
     * same treatment as `images` above and for the same reason. A field that
     * only the detail screen reads has no business being serialized into
     * every card of every feed page, on a phone that may be on mobile data.
     */
    private final String model3dUrl;

    /**
     * Kept so existing callers - and existing cached entries - constructed
     * without a gallery keep compiling and behaving identically. Delegates
     * with an empty gallery rather than null.
     */
    public ProductResponse(Long id, String name, String brand, CategoryResponse category,
                            List<VariantResponse> variants, Boolean active) {
        this(id, name, brand, category, variants, active, List.of(), null);
    }

    /**
     * Kept at this arity so every existing caller and every cached entry
     * written before the catalog fields existed keeps working unchanged.
     */
    public ProductResponse(Long id, String name, String brand, CategoryResponse category,
                            List<VariantResponse> variants, Boolean active, List<String> images,
                            String model3dUrl) {
        this(id, name, brand, category, variants, active, images, model3dUrl,
             null, null, null, null);
    }

    public ProductResponse(Long id, String name, String brand, CategoryResponse category,
                            List<VariantResponse> variants, Boolean active, List<String> images,
                            String model3dUrl, String subcategory, Boolean bestseller,
                            Boolean featured, Boolean testData) {
        this.id = id;
        this.name = name;
        this.brand = brand;
        this.category = category;
        this.variants = variants;
        this.active = active;
        this.images = images == null ? List.of() : images;
        this.model3dUrl = model3dUrl;
        this.subcategory = subcategory;
        this.bestseller = bestseller;
        this.featured = featured;
        this.testData = testData;
    }

    /**
     * Same product, with its gallery attached.
     *
     * A copy rather than a setter because this DTO is immutable and is
     * handed to a shared cache - mutating one would mutate whatever else
     * holds the reference.
     */
    public ProductResponse withImages(List<String> galleryUrls) {
        return new ProductResponse(id, name, brand, category, variants, active, galleryUrls,
                model3dUrl, subcategory, bestseller, featured, testData);
    }

    /**
     * Same product, told that it has a 3D model. Detail-only, like
     * withImages - a list response deliberately never carries this.
     */
    public ProductResponse withModel3dUrl(String url) {
        return new ProductResponse(id, name, brand, category, variants, active, images,
                url, subcategory, bestseller, featured, testData);
    }

    public static ProductResponse from(Product product) {
        if (product == null) {
            return null;
        }
        List<VariantResponse> variants = product.getVariants() == null
                ? List.of()
                : product.getVariants().stream()
                        .map(VariantResponse::from)
                        .collect(Collectors.toList());

        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getBrand(),
                CategoryResponse.from(product.getCategory()),
                variants,
                product.getActive(),
                List.of(),
                // null, NOT product.getModel3dUrl(). from() is what every LIST
                // endpoint maps through, and the 3D url is detail-only by
                // design - it arrives via withModel3dUrl(). Populating it here
                // would put a field one screen reads onto every feed page.
                null,
                product.getSubcategory(),
                product.getBestseller(),
                product.getFeatured(),
                product.getIsTestData()
        );
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getBrand() { return brand; }
    public CategoryResponse getCategory() { return category; }
    public List<VariantResponse> getVariants() { return variants; }
    public Boolean getActive() { return active; }

    /** Never null - empty when the product has no gallery images. */
    public List<String> getImages() { return images; }

    public String getSubcategory() { return subcategory; }
    public Boolean getBestseller() { return bestseller; }
    public Boolean getFeatured() { return featured; }
    public Boolean getTestData() { return testData; }

    /** Null unless this came from the product detail endpoint AND the product has a model. */
    public String getModel3dUrl() { return model3dUrl; }
}
