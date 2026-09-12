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
    private final java.util.List<com.gpstore.rating.ProductReviewReason> reasons;
    private final String merchantResponse;
    private final LocalDateTime merchantResponseAt;
    private final String customerReply;
    private final LocalDateTime customerReplyAt;
    private final boolean hidden;

    public ReviewResponse(Long id, Long productId, String productName,
                           Integer rating, String comment, LocalDateTime reviewDate) {
        this(id, productId, productName, rating, comment, reviewDate,
                java.util.List.of(), null, null, null, null, false);
    }

    public ReviewResponse(Long id, Long productId, String productName,
                           Integer rating, String comment, LocalDateTime reviewDate,
                           java.util.List<com.gpstore.rating.ProductReviewReason> reasons,
                           String merchantResponse, LocalDateTime merchantResponseAt,
                           String customerReply, LocalDateTime customerReplyAt,
                           boolean hidden) {
        this.id = id;
        this.productId = productId;
        this.productName = productName;
        this.rating = rating;
        this.comment = comment;
        this.reviewDate = reviewDate;
        this.reasons = reasons;
        this.merchantResponse = merchantResponse;
        this.merchantResponseAt = merchantResponseAt;
        this.customerReply = customerReply;
        this.customerReplyAt = customerReplyAt;
        this.hidden = hidden;
    }

    public static ReviewResponse from(Review review) {
        var product = review.getProduct();
        return new ReviewResponse(
                review.getId(),
                product != null ? product.getId() : null,
                product != null ? product.getName() : null,
                review.getRating(),
                review.getComment(),
                review.getReviewDate(),
                review.getReasons() == null
                        ? java.util.List.of() : java.util.List.copyOf(review.getReasons()),
                review.getMerchantResponse(),
                review.getMerchantResponseAt(),
                review.getCustomerReply(),
                review.getCustomerReplyAt(),
                !review.isVisible()
        );
    }

    public Long getId() { return id; }
    public Long getProductId() { return productId; }
    public String getProductName() { return productName; }
    public Integer getRating() { return rating; }
    public String getComment() { return comment; }
    public LocalDateTime getReviewDate() { return reviewDate; }
    public java.util.List<com.gpstore.rating.ProductReviewReason> getReasons() { return reasons; }
    public String getMerchantResponse() { return merchantResponse; }
    public LocalDateTime getMerchantResponseAt() { return merchantResponseAt; }
    public String getCustomerReply() { return customerReply; }
    public LocalDateTime getCustomerReplyAt() { return customerReplyAt; }
    public boolean isHidden() { return hidden; }
}
