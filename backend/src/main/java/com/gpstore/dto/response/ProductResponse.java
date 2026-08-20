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
     * Kept so existing callers - and existing cached entries - constructed
     * without a gallery keep compiling and behaving identically. Delegates
     * with an empty gallery rather than null.
     */
    public ProductResponse(Long id, String name, String brand, CategoryResponse category,
                            List<VariantResponse> variants, Boolean active) {
        this(id, name, brand, category, variants, active, List.of());
    }

    public ProductResponse(Long id, String name, String brand, CategoryResponse category,
                            List<VariantResponse> variants, Boolean active, List<String> images) {
        this.id = id;
        this.name = name;
        this.brand = brand;
        this.category = category;
        this.variants = variants;
        this.active = active;
        this.images = images == null ? List.of() : images;
    }

    /**
     * Same product, with its gallery attached.
     *
     * A copy rather than a setter because this DTO is immutable and is
     * handed to a shared cache - mutating one would mutate whatever else
     * holds the reference.
     */
    public ProductResponse withImages(List<String> galleryUrls) {
        return new ProductResponse(id, name, brand, category, variants, active, galleryUrls);
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
                product.getActive()
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
}
