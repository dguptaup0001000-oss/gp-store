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

    /**
     * Moderation: stop showing a review. It is NOT deleted (§20).
     *
     * <p>THE REASON IS REQUIRED, and it has to be one of a closed list whose
     * values all describe something wrong with the TEXT - abuse, somebody's
     * phone number, spam - and none of which describe something wrong with
     * the OPINION. There is no way to express "this review is unfair"
     * through this endpoint, which is the whole of §20 in one parameter.
     *
     * <p>THIS USED TO BE A HARD DELETE with no reason and no record. A
     * client calling it the old way now gets a 400 naming the list rather
     * than silently destroying a customer's words.
     */
    @DeleteMapping("/{id}/moderate")
    public com.gpstore.dto.response.ReviewResponse moderateHideReview(
            @PathVariable Long id, @RequestParam(required = false) String reason) {
        return reviewService.hideReview(id, parseHideReason(reason), actor());
    }

    /** Puts one back. */
    @PostMapping("/{id}/unhide")
    public com.gpstore.dto.response.ReviewResponse unhide(@PathVariable Long id) {
        return reviewService.unhideReview(id, actor());
    }

    /** The shop's one response to a review of something it sells (§21). */
    @PostMapping("/{id}/respond")
    public com.gpstore.dto.response.ReviewResponse respond(
            @PathVariable Long id, @RequestBody java.util.Map<String, String> request) {
        // The shop comes from the credential's scope, never from the body.
        com.gpstore.platform.TenantScope scope = com.gpstore.platform.TenantContext.current();
        Long shopId = scope == null || scope.isPlatform() ? null : scope.shopId();
        return reviewService.merchantRespond(id, request.get("text"), shopId, actor());
    }

    /** The customer's one reply to that response (§21). */
    @PostMapping("/{id}/reply")
    public com.gpstore.dto.response.ReviewResponse reply(
            @PathVariable Long id, @RequestBody java.util.Map<String, String> request) {
        return reviewService.customerReply(id, currentUser.customerId(), request.get("text"));
    }

    /** A shop flags a review for a platform reviewer (§22). It stays visible. */
    @PostMapping("/{id}/report")
    public com.gpstore.dto.response.ReviewResponse report(
            @PathVariable Long id, @RequestBody java.util.Map<String, String> request) {
        return reviewService.report(id, request.get("reason"), actor());
    }

    /** The moderation queue: what has been flagged. */
    @GetMapping("/reported")
    public Page<com.gpstore.dto.response.AdminReviewResponse> reported(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return reviewService.reported(PageRequest.of(page, Math.min(size, 100)));
    }

    /** The closed list, so a moderation screen draws it rather than invents one. */
    @GetMapping("/hide-reasons")
    public List<com.gpstore.rating.HideReason> hideReasons() {
        return List.of(com.gpstore.rating.HideReason.values());
    }

    /** A product's stars, as a product page shows them (§19). */
    @GetMapping("/product/{productId}/summary")
    public com.gpstore.rating.RatingTally productSummary(@PathVariable Long productId) {
        return reviewService.tallyFor(productId);
    }

    private String actor() {
        return "admin:" + currentUser.customerId();
    }

    private static com.gpstore.rating.HideReason parseHideReason(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new com.gpstore.exception.BadRequestException(
                    "A reason is required to hide a review. §20 keeps genuine negative "
                            + "reviews, so the list is closed: "
                            + java.util.Arrays.toString(com.gpstore.rating.HideReason.values()));
        }
        try {
            return com.gpstore.rating.HideReason.valueOf(
                    raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new com.gpstore.exception.BadRequestException(
                    "'" + raw + "' is not a reason a review may be hidden for: "
                            + java.util.Arrays.toString(com.gpstore.rating.HideReason.values()));
        }
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
