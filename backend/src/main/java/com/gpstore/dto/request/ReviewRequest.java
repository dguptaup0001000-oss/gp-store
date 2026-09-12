package com.gpstore.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class ReviewRequest {

    @NotNull(message = "Product ID is required")
    private Long productId;

    @NotNull(message = "Rating is required")
    @Min(value = 1, message = "Rating must be at least 1")
    @Max(value = 5, message = "Rating cannot exceed 5")
    private Integer rating;

    @Size(max = 1000, message = "Comment cannot exceed 1000 characters")
    private String comment;

    /**
     * WHY, in codes a shop can count (§18).
     *
     * <p>Optional: a customer who only wants to leave four stars should not
     * be made to justify them. @Size caps it because a review that ticks
     * every box says nothing anybody can act on.
     */
    @Size(max = 5, message = "Pick at most 5 reasons")
    private java.util.Set<com.gpstore.rating.ProductReviewReason> reasons;

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public java.util.Set<com.gpstore.rating.ProductReviewReason> getReasons() { return reasons; }
    public void setReasons(java.util.Set<com.gpstore.rating.ProductReviewReason> reasons) {
        this.reasons = reasons;
    }
}
