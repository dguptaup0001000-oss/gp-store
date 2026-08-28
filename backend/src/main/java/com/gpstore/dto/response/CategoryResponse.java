package com.gpstore.dto.response;

import com.gpstore.entity.Category;

import java.io.Serializable;

/**
 * Explicit response shape instead of returning the raw Category entity.
 * Nested inside ProductResponse - see ProductResponse for why Product itself
 * needs this.
 *
 * Serializable - see ProductResponse's doc comment (this is nested inside it).
 */
public class CategoryResponse implements Serializable {

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
    private final String description;
    private final String imageUrl;
    private final Double gstRate;
    private final Boolean active;

    public CategoryResponse(Long id, String name, String description, String imageUrl,
                             Double gstRate, Boolean active) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.imageUrl = imageUrl;
        this.gstRate = gstRate;
        this.active = active;
    }

    public static CategoryResponse from(Category category) {
        if (category == null) {
            return null;
        }
        return new CategoryResponse(
                category.getId(),
                category.getName(),
                category.getDescription(),
                category.getImageUrl() == null
                        ? null
                        : com.gpstore.upload.CatalogImageDelivery.forClient(category.getImageUrl()),
                category.getGstRate() != null ? category.getGstRate().doubleValue() : null,
                category.getActive()
        );
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getImageUrl() { return imageUrl; }
    public Double getGstRate() { return gstRate; }
    public Boolean getActive() { return active; }
}
