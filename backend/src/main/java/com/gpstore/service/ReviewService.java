package com.gpstore.service;

import com.gpstore.dto.request.ReviewRequest;
import com.gpstore.entity.Customer;
import com.gpstore.entity.Product;
import com.gpstore.entity.Review;
import com.gpstore.dto.response.AdminReviewResponse;
import com.gpstore.dto.response.ReviewResponse;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ConflictException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.rating.HideReason;
import com.gpstore.repository.OrderItemRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ReviewRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ProductRepository productRepository;
    private final CustomerService customerService;
    private final OrderItemRepository orderItemRepository;
    private final AuditLogService auditLogService;

    public ReviewService(ReviewRepository reviewRepository, ProductRepository productRepository,
                          CustomerService customerService, OrderItemRepository orderItemRepository,
                          AuditLogService auditLogService) {
        this.reviewRepository = reviewRepository;
        this.productRepository = productRepository;
        this.customerService = customerService;
        this.orderItemRepository = orderItemRepository;
        this.auditLogService = auditLogService;
    }

    /**
     * Verified-purchase-only reviews: a customer can only review a product
     * they've actually ordered - this is what stops a review section from
     * filling up with reviews from people (or competitors) who never bought
     * anything. Posting a second review for the same product updates the
     * existing one instead of creating a duplicate (same as most real
     * storefronts - one review per customer per product).
     */
    public ReviewResponse submitReview(Long customerId, ReviewRequest request) {

        boolean hasPurchased = orderItemRepository
                .existsByOrder_Customer_IdAndProductVariant_Product_Id(customerId, request.getProductId());

        if (!hasPurchased) {
            throw new BadRequestException("You can only review products you've purchased");
        }

        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        Customer customer = customerService.getById(customerId);

        Review review = reviewRepository.findByCustomerIdAndProductId(customerId, request.getProductId())
                .orElseGet(Review::new);

        review.setCustomer(customer);
        review.setProduct(product);
        review.setRating(request.getRating());
        review.setComment(request.getComment());
        review.setReviewDate(LocalDateTime.now());
        review.setActive(true);
        // §18: the reason codes, replaced wholesale on each edit so that
        // removing one actually removes it.
        review.setReasons(request.getReasons() == null
                ? new java.util.LinkedHashSet<>()
                : new java.util.LinkedHashSet<>(request.getReasons()));

        return ReviewResponse.from(reviewRepository.save(review));
    }

    @Transactional(readOnly = true)
    /**
     * The review list a shopkeeper works from.
     *
     * <p>WAS findAll(). Every product review on GP-STORE, for every product, by
     * every customer of every merchant - and AdminReviewResponse carries the
     * reviewer's name AND email. Review is not a ShopOwned entity (a review
     * belongs to a PRODUCT, which is central and shared), so no tenant filter
     * narrowed it, and the only gate was REVIEWS_MODERATE - which every shop
     * owner holds through Role.ADMIN's EVERY_SHOP_PERMISSION. A merchant
     * onboarded an hour ago, selling nothing, could page through the name and
     * email address of everyone who had ever reviewed anything here.
     *
     * <p>A merchant now reads reviews of what they list, without the email. The
     * platform keeps the whole picture, unmasked - see AdminReviewResponse.
     */
    public Page<AdminReviewResponse> getAllReviews(Pageable pageable) {
        if (readsAcrossShops()) {
            return reviewRepository.findAll(pageable).map(AdminReviewResponse::from);
        }
        return reviewRepository.findAllForCurrentShopShelf(pageable)
                .map(review -> AdminReviewResponse.from(review, false));
    }

    /**
     * Whether the caller reads the whole platform or one shop's shelf.
     *
     * <p>Platform scope is the platform owner's console. A shop scope is a
     * shopkeeper, however many permissions their role carries.
     */
    private boolean readsAcrossShops() {
        com.gpstore.platform.TenantScope scope = com.gpstore.platform.TenantContext.current();
        return scope == null || scope.isPlatform();
    }

    /**
     * Refuses a merchant acting on a review of something they do not sell.
     *
     * <p>A review is attached to a PRODUCT, and a product is shared by every
     * shop selling it - so one merchant hiding a review takes it off every
     * other merchant's storefront too, and one merchant answering it puts a
     * stranger's words under another shop's item. The platform may do both;
     * a shopkeeper may do them on their own shelf.
     */
    private void requireOnMyShelf(Long reviewId) {
        if (readsAcrossShops()) {
            return;
        }
        if (!reviewRepository.isOnCurrentShopShelf(reviewId)) {
            throw new com.gpstore.platform.CrossShopAccessException(
                    "That review is on a product this shop does not sell.");
        }
    }

    @Transactional(readOnly = true)
        public Page<ReviewResponse> getForProduct(Long productId, Pageable pageable) {
            // §20: hidden reviews are not deleted, they are not SHOWN. This
            // is the query that makes the difference real.
            return reviewRepository
                    .findByProductIdAndActiveTrueAndHiddenAtIsNullOrderByReviewDateDesc(
                            productId, pageable)
                    .map(ReviewResponse::from);
        }

    private static final int MY_REVIEWS_CAP = 100;

    @Transactional(readOnly = true)
    public List<ReviewResponse> getMyReviews(Long customerId) {
        return reviewRepository
                .findByCustomerIdOrderByReviewDateDesc(customerId, PageRequest.of(0, MY_REVIEWS_CAP))
                .stream()
                .map(ReviewResponse::from)
                .toList();
    }

    public void deleteOwnReview(Long id, Long customerId) {
        Review review = reviewRepository.findByIdAndCustomerId(id, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found"));
        reviewRepository.delete(review);
    }

    // ------------------------------------------------------------------
    // Moderation, the conversation, and reporting (Part 3 §18, §20-§22).
    // ------------------------------------------------------------------

    /**
     * Stops a review being SHOWN. It is not deleted (§20).
     *
     * <p>WHAT THIS REPLACES, AND WHY IT HAD TO CHANGE. This used to be
     * {@code reviewRepository.delete(review)} behind an admin permission -
     * one call, no reason, no record. §20 says genuine negative reviews must
     * remain, and a hard delete makes that unenforceable and unauditable at
     * once: the row is gone, so nobody can ask afterwards whether the shop's
     * one-star reviews keep disappearing.
     *
     * <p>Now: a reason from a closed list (see {@link HideReason} for what is
     * deliberately not in it), the moderator's name on the row, an audit
     * entry, and the stars usually still counting towards the product's
     * average - because if hiding also improved the average, hiding would be
     * worth doing for the arithmetic alone.
     */
    @Transactional
    public ReviewResponse hideReview(Long id, HideReason reason, String actor) {
        requireOnMyShelf(id);
        if (reason == null) {
            throw new BadRequestException(
                    "Hiding a review needs a reason, and it has to be one of: "
                            + java.util.Arrays.toString(HideReason.values()));
        }
        Review review = reviewRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found"));
        review.setHiddenAt(LocalDateTime.now());
        review.setHiddenReason(reason);
        review.setHiddenBy(actor);
        Review saved = reviewRepository.save(review);
        auditLogService.log("REVIEW_HIDDEN", "Review", saved.getId(),
                reason + " by " + actor
                        + (reason.stillCounts() ? " (still counts towards the average)"
                                                : " (removed from the average)"));
        return ReviewResponse.from(saved);
    }

    /** Puts one back. An un-hide is a decision too, so it is recorded too. */
    @Transactional
    public ReviewResponse unhideReview(Long id, String actor) {
        requireOnMyShelf(id);
        Review review = reviewRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found"));
        review.setHiddenAt(null);
        review.setHiddenReason(null);
        review.setHiddenBy(null);
        Review saved = reviewRepository.save(review);
        auditLogService.log("REVIEW_RESTORED", "Review", saved.getId(), "by " + actor);
        return ReviewResponse.from(saved);
    }

    /**
     * The shop answers a review of something it sells, once (§21).
     *
     * <p>ONCE, and enforced here. A merchant who could post repeatedly could
     * bury a one-star review under their own replies, which is §20's problem
     * reached by a different road.
     *
     * <p>The responding shop is recorded because a product review is central
     * (§10) while a reply to one is not - it is one shopkeeper speaking, and
     * a customer reading it on a different shop's product page deserves to
     * know which shop said it.
     */
    @Transactional
    public ReviewResponse merchantRespond(Long id, String text, Long shopId, String actor) {
        requireOnMyShelf(id);
        String response = trimmedOrNull(text);
        if (response == null) {
            throw new BadRequestException("Write something for the customer to read.");
        }
        Review review = reviewRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found"));
        if (review.getMerchantResponse() != null) {
            throw new ConflictException(
                    "This review has already been answered. One response each (§21).");
        }
        review.setMerchantResponse(response);
        review.setMerchantResponseAt(LocalDateTime.now());
        review.setMerchantResponseBy(actor);
        review.setRespondingShopId(shopId);
        Review saved = reviewRepository.save(review);
        auditLogService.log("REVIEW_ANSWERED", "Review", saved.getId(), "by " + actor);
        return ReviewResponse.from(saved);
    }

    /** The customer's single reply to that single response (§21). */
    @Transactional
    public ReviewResponse customerReply(Long id, Long customerId, String text) {
        String reply = trimmedOrNull(text);
        if (reply == null) {
            throw new BadRequestException("Write something for the shop to read.");
        }
        Review review = reviewRepository.findByIdAndCustomerId(id, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found"));
        if (review.getMerchantResponse() == null) {
            throw new ConflictException("The shop has not responded yet.");
        }
        if (review.getCustomerReply() != null) {
            throw new ConflictException("You have already replied. One response each (§21).");
        }
        review.setCustomerReply(reply);
        review.setCustomerReplyAt(LocalDateTime.now());
        return ReviewResponse.from(reviewRepository.save(review));
    }

    /**
     * A shop flags a review for a platform reviewer (§22).
     *
     * <p>REPORTING DOES NOT HIDE IT. A merchant able to suppress a review by
     * objecting to it would have a delete button with an extra step.
     */
    @Transactional
    public ReviewResponse report(Long id, String reason, String actor) {
        Review review = reviewRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found"));
        if (review.getReportedAt() == null) {
            review.setReportedAt(LocalDateTime.now());
            review.setReportedBy(actor);
            review.setReportReason(reason == null || reason.isBlank()
                    ? null
                    : reason.trim().substring(0, Math.min(reason.trim().length(), 300)));
            review = reviewRepository.save(review);
            auditLogService.log("REVIEW_REPORTED", "Review", review.getId(),
                    "reported by " + actor + (reason == null ? "" : ": " + reason));
        }
        return ReviewResponse.from(review);
    }

    @Transactional(readOnly = true)
    public Page<AdminReviewResponse> reported(Pageable pageable) {
        // A second listing endpoint over the same rows is a second leak, so it
        // is narrowed identically rather than left as the one that got away.
        if (readsAcrossShops()) {
            return reviewRepository.findByReportedAtIsNotNullOrderByReportedAtDesc(pageable)
                    .map(AdminReviewResponse::from);
        }
        return reviewRepository.findReportedForCurrentShopShelf(pageable)
                .map(review -> AdminReviewResponse.from(review, false));
    }

    /** A product's stars as a page shows them (§19). */
    @Transactional(readOnly = true)
    public com.gpstore.rating.RatingTally tallyFor(Long productId) {
        com.gpstore.rating.RatingTally tally = reviewRepository.tallyForProduct(productId);
        return tally == null ? com.gpstore.rating.RatingTally.empty() : tally;
    }

    private static String trimmedOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= 1000 ? trimmed : trimmed.substring(0, 1000);
    }
}
