package com.gpstore.controller;

import com.gpstore.dto.request.ReviewRequest;
import com.gpstore.entity.Review;
import com.gpstore.security.CurrentUser;
import com.gpstore.service.ReviewService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewService reviewService;
    private final CurrentUser currentUser;

    public ReviewController(ReviewService reviewService, CurrentUser currentUser) {
        this.reviewService = reviewService;
        this.currentUser = currentUser;
    }

    // Verified-purchase-only - customer is always the logged-in caller, never
    // trusted from the request body. Posting again updates your existing review.
    @PostMapping
    public com.gpstore.dto.response.ReviewResponse submitReview(@Valid @RequestBody ReviewRequest request) {
        return reviewService.submitReview(currentUser.customerId(), request);
    }

    // Admin only (enforced in SecurityConfig) - includes customer name/email
    // for moderation, which the raw entity deliberately hides (see
    // AdminReviewResponse's doc comment for why that needed its own DTO).
    @GetMapping
    public Page<com.gpstore.dto.response.AdminReviewResponse> getAllReviews(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        return reviewService.getAllReviews(pageable);
    }

    // Admin only - moderation: remove any review, not just your own.
    @DeleteMapping("/{id}/moderate")
    public String moderateDeleteReview(@PathVariable Long id) {
        reviewService.moderateDeleteReview(id);
        return "Review removed";
    }

    // Public - what a product page actually shows.
    @GetMapping("/product/{productId}")
    public Page<com.gpstore.dto.response.ReviewResponse> getForProduct(
            @PathVariable Long productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        return reviewService.getForProduct(productId, pageable);
    }

    // The logged-in customer's own reviews.
    @GetMapping("/mine")
    public List<com.gpstore.dto.response.ReviewResponse> getMyReviews() {
        return reviewService.getMyReviews(currentUser.customerId());
    }

    @DeleteMapping("/{id}")
    public String deleteMyReview(@PathVariable Long id) {
        reviewService.deleteOwnReview(id, currentUser.customerId());
        return "Review deleted";
    }
}
