package com.gpstore.dto.response;

import com.gpstore.entity.Review;

import java.time.LocalDateTime;

/**
 * Customer-facing review shape - unlike AdminReviewResponse, this never
 * exposes who wrote the review, since the caller already knows (it's either
 * "my own reviews" or a public product page where the author isn't shown).
 */
public class ReviewResponse {

    private final Long id;
    private final Long productId;
    private final String productName;
    private final Integer rating;
    private final String comment;
    private final LocalDateTime reviewDate;

    public ReviewResponse(Long id, Long productId, String productName,
                           Integer rating, String comment, LocalDateTime reviewDate) {
        this.id = id;
        this.productId = productId;
        this.productName = productName;
        this.rating = rating;
        this.comment = comment;
        this.reviewDate = reviewDate;
    }

    public static ReviewResponse from(Review review) {
        var product = review.getProduct();
        return new ReviewResponse(
                review.getId(),
                product != null ? product.getId() : null,
                product != null ? product.getName() : null,
                review.getRating(),
                review.getComment(),
                review.getReviewDate()
        );
    }

    public Long getId() { return id; }
    public Long getProductId() { return productId; }
    public String getProductName() { return productName; }
    public Integer getRating() { return rating; }
    public String getComment() { return comment; }
    public LocalDateTime getReviewDate() { return reviewDate; }
}
