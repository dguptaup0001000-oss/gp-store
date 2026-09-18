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

    /**
     * The platform's view: everything, including the reviewer's email.
     *
     * <p>UNMASKED ON PURPOSE. The platform owner moderates GP-STORE and needs
     * to be able to reach a reviewer; narrowing them would be a different bug.
     */
    public static AdminReviewResponse from(Review review) {
        return from(review, true);
    }

    /**
     * @param withContactDetail whether the reviewer's email belongs in this
     *     answer. False for a merchant.
     *
     * <p>WHY A MERCHANT DOES NOT GET THE EMAIL. Answering a review needs the
     * review - the stars, the words, the product. It does not need a way to
     * contact the person who wrote it, and a merchant page that quietly
     * returned one was handing every shopkeeper a list of reviewers' addresses
     * they had no reason to hold. The name stays: a reply reading "Thanks
     * Priya" is the point of a reply.
     */
    public static AdminReviewResponse from(Review review, boolean withContactDetail) {
        var customer = review.getCustomer();
        var product = review.getProduct();

        return new AdminReviewResponse(
                review.getId(),
                customer != null ? customer.getFullName() : null,
                withContactDetail && customer != null ? customer.getEmail() : null,
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
