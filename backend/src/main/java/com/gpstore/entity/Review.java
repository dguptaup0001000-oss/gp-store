package com.gpstore.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "reviews")
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private Customer customer;

    // Deliberately left EAGER: ReviewController.getForProduct()/getMyReviews()
    // return raw Review entities with no @Transactional on the service methods,
    // so Jackson serializes after the session closes - LAZY here throws
    // LazyInitializationException on those endpoints. Revisit alongside a DTO
    // refactor of ReviewController, not in isolation.
    @ManyToOne(fetch = FetchType.LAZY)
    private Product product;

    private Integer rating;

    private String comment;

    private LocalDateTime reviewDate;

    private Boolean active;

    /**
     * WHY the customer rated the item that way (§18).
     *
     * <p>Rows rather than a joined-up string, for the same reason the shop
     * rating's reasons are: "how many reviews of this dal say NOT_FRESH" is
     * the question worth asking, and a comma-separated column cannot be
     * grouped by.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "product_review_reasons",
            joinColumns = @JoinColumn(name = "review_id"))
    @Column(name = "reason", length = 40, nullable = false)
    @Enumerated(EnumType.STRING)
    private java.util.Set<com.gpstore.rating.ProductReviewReason> reasons =
            new java.util.LinkedHashSet<>();

    // ------------------------------------------------ §21 one response each
    //
    // The same four columns the shop rating carries, deliberately identical.
    // Two shapes for one idea is how the shop side ends up with an appeal
    // route the product side quietly lacks.

    @Column(name = "merchant_response", length = 1000)
    private String merchantResponse;

    @Column(name = "merchant_response_at")
    private LocalDateTime merchantResponseAt;

    @Column(name = "merchant_response_by", length = 120)
    private String merchantResponseBy;

    /**
     * WHICH SHOP ANSWERED. A product is central (§10) and its reviews are
     * shared by every shop selling it, but a reply is not central - it is one
     * shopkeeper answering a customer who bought from THEM. Null until
     * somebody replies.
     */
    @Column(name = "responding_shop_id")
    private Long respondingShopId;

    @Column(name = "customer_reply", length = 1000)
    private String customerReply;

    @Column(name = "customer_reply_at")
    private LocalDateTime customerReplyAt;

    // ------------------------------------------------- §20 hidden, not gone

    @Column(name = "hidden_at")
    private LocalDateTime hiddenAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "hidden_reason", length = 40)
    private com.gpstore.rating.HideReason hiddenReason;

    @Column(name = "hidden_by", length = 120)
    private String hiddenBy;

    // ---------------------------------------------------- §22 reporting

    @Column(name = "reported_at")
    private LocalDateTime reportedAt;

    @Column(name = "reported_by", length = 120)
    private String reportedBy;

    @Column(name = "report_reason", length = 300)
    private String reportReason;

    /** Whether the words are shown to other customers. */
    public boolean isVisible() {
        return hiddenAt == null;
    }

    /** Whether the stars still count - see HideReason.stillCounts. */
    public boolean countsTowardsTheAverage() {
        return hiddenAt == null || (hiddenReason != null && hiddenReason.stillCounts());
    }
}