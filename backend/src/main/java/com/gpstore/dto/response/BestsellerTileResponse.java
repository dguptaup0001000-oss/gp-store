package com.gpstore.dto.response;

import java.io.Serializable;
import java.util.List;

/**
 * One tile of the home screen's Bestsellers collage: a category and the few
 * product images shown inside it.
 *
 * DELIBERATELY NOT ProductResponse. The tile draws a 2x2 grid of thumbnails
 * and the category's name - it never reads a price, a description, a rating,
 * a stock flag or a variant list. Returning full products would hydrate all
 * of that, pay the mapping cost for it, and push it over mobile data to be
 * discarded on arrival. Twenty-four image URLs are roughly two kilobytes;
 * twenty-four ProductResponse objects are tens of kilobytes, and every byte
 * of the difference is serialized by a 0.5 vCPU instance.
 *
 * productIds are still carried, so a tile can navigate or be instrumented
 * later without another shape change, and so a caller can tell "four
 * products, none with an image" from "no products".
 *
 * Serializable for the same reason as ProductResponse: this endpoint is
 * @Cacheable and Spring's default RedisCacheManager uses JDK serialization,
 * which requires the whole graph to support it.
 */
public class BestsellerTileResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long categoryId;
    private String categoryName;
    private List<Long> productIds;

    /**
     * Parallel to productIds, and nullable per entry: a product with no
     * variants, or a variant with no photo, still occupies its slot in the
     * collage and renders the placeholder icon the UI already draws. Dropping
     * it instead would silently shift the other three thumbnails.
     */
    private List<String> imageUrls;

    /**
     * How many active products the category holds altogether - what the tile
     * renders as "+N more" under the collage.
     *
     * NOT productIds.size(). That is four, always, because four is how many
     * thumbnails the collage draws. A shopper looking at a tile wants to know
     * whether tapping it opens a shelf of six or of six hundred, and the only
     * honest answer to that is a real count from the database.
     */
    private long productCount;

    public BestsellerTileResponse() {
    }

    public BestsellerTileResponse(Long categoryId, String categoryName,
                                  List<Long> productIds, List<String> imageUrls,
                                  long productCount) {
        this.categoryId = categoryId;
        this.categoryName = categoryName;
        this.productIds = productIds;
        this.imageUrls = imageUrls;
        this.productCount = productCount;
    }

    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }

    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }

    public List<Long> getProductIds() { return productIds; }
    public void setProductIds(List<Long> productIds) { this.productIds = productIds; }

    public List<String> getImageUrls() { return imageUrls; }
    public void setImageUrls(List<String> imageUrls) { this.imageUrls = imageUrls; }

    public long getProductCount() { return productCount; }
    public void setProductCount(long productCount) { this.productCount = productCount; }
}
