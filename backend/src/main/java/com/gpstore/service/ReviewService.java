package com.gpstore.service;

import com.gpstore.dto.request.ReviewRequest;
import com.gpstore.entity.Customer;
import com.gpstore.entity.Product;
import com.gpstore.entity.Review;
import com.gpstore.dto.response.AdminReviewResponse;
import com.gpstore.dto.response.ReviewResponse;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.OrderItemRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ReviewRepository;
import org.springframework.data.domain.Page;
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

    public ReviewService(ReviewRepository reviewRepository, ProductRepository productRepository,
                          CustomerService customerService, OrderItemRepository orderItemRepository) {
        this.reviewRepository = reviewRepository;
        this.productRepository = productRepository;
        this.customerService = customerService;
        this.orderItemRepository = orderItemRepository;
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

        return ReviewResponse.from(reviewRepository.save(review));
    }

    @Transactional(readOnly = true)
    public Page<AdminReviewResponse> getAllReviews(Pageable pageable) {
        return reviewRepository.findAll(pageable).map(AdminReviewResponse::from);
    }

    @Transactional(readOnly = true)
        public Page<ReviewResponse> getForProduct(Long productId, Pageable pageable) {
            return reviewRepository.findByProductIdAndActiveTrueOrderByReviewDateDesc(productId, pageable)
                    .map(ReviewResponse::from);
        }

    @Transactional(readOnly = true)
        public List<ReviewResponse> getMyReviews(Long customerId) {
            return reviewRepository.findByCustomerId(customerId).stream().map(ReviewResponse::from).toList();
        }

    public void deleteOwnReview(Long id, Long customerId) {
        Review review = reviewRepository.findByIdAndCustomerId(id, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found"));
        reviewRepository.delete(review);
    }

    /**
     * Moderation - admin can remove ANY review (e.g. abusive/spam content),
     * not just their own. This didn't exist before: admin could view every
     * review but had no way to actually act on a bad one.
     */
    public void moderateDeleteReview(Long id) {
        Review review = reviewRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found"));
        reviewRepository.delete(review);
    }
}
