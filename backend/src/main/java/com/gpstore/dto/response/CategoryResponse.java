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
                category.getImageUrl(),
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
