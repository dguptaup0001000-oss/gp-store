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

    private final Long id;
    private final String name;
    private final String brand;
    private final CategoryResponse category;
    private final List<VariantResponse> variants;
    private final Boolean active;

    public ProductResponse(Long id, String name, String brand, CategoryResponse category,
                            List<VariantResponse> variants, Boolean active) {
        this.id = id;
        this.name = name;
        this.brand = brand;
        this.category = category;
        this.variants = variants;
        this.active = active;
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
}
