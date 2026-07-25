package com.gpstore.dto.response;

import com.gpstore.entity.Review;

import java.time.LocalDateTime;

/**
 * Review.customer is deliberately @JsonIgnore'd on the raw entity (correct
 * for customer-facing use - it's always "me"). For admin moderation,
 * that's backwards: admin needs to know WHO wrote a review to act on it.
 * This DTO exists specifically to bridge that gap.
 */
public class AdminReviewResponse {

    private final Long id;
    private final String customerName;
    private final String customerEmail;
    private final Long productId;
    private final String productName;
    private final Integer rating;
    private final String comment;
    private final LocalDateTime reviewDate;

    public AdminReviewResponse(Long id, String customerName, String customerEmail, Long productId,
                                String productName, Integer rating, String comment, LocalDateTime reviewDate) {
        this.id = id;
        this.customerName = customerName;
        this.customerEmail = customerEmail;
        this.productId = productId;
        this.productName = productName;
        this.rating = rating;
        this.comment = comment;
        this.reviewDate = reviewDate;
    }

    public static AdminReviewResponse from(Review review) {
        var customer = review.getCustomer();
        var product = review.getProduct();

        return new AdminReviewResponse(
                review.getId(),
                customer != null ? customer.getFullName() : null,
                customer != null ? customer.getEmail() : null,
                product != null ? product.getId() : null,
                product != null ? product.getName() : null,
                review.getRating(),
                review.getComment(),
                review.getReviewDate()
        );
    }

    public Long getId() { return id; }
    public String getCustomerName() { return customerName; }
    public String getCustomerEmail() { return customerEmail; }
    public Long getProductId() { return productId; }
    public String getProductName() { return productName; }
    public Integer getRating() { return rating; }
    public String getComment() { return comment; }
    public LocalDateTime getReviewDate() { return reviewDate; }
}
