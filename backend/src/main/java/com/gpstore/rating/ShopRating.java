package com.gpstore.rating;

import com.gpstore.platform.ShopOwned;
import com.gpstore.platform.ShopScopeFilter;
import com.gpstore.platform.TenantEntityListener;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What a customer thought of the SHOP, on one order (§17).
 *
 * <p>NOT OF THE PRODUCT. The product has its own review, central to the
 * catalogue and shared by every shop selling it. This is about the kirana:
 * whether the order came when it was promised, whether everything was in the
 * bag, whether the person on the phone was helpful. §17 separates them
 * because a shop that delivered a perfect packet of a mediocre biscuit should
 * not carry the biscuit's stars.
 *
 * <p>SHOP-OWNED, and that settles three questions at once: a merchant may
 * read only their own ratings, may answer only their own, and may report only
 * their own. None of that needs a check in a service - it is the same filter
 * that scopes every other shop-owned row.
 *
 * <p>ONE PER ORDER. §22's first and best defence against rating abuse is that
 * a rating has to be paid for with a real order, and that an order buys
 * exactly one. Enforced by a unique index, because "check then insert" is two
 * requests away from being wrong.
 */
@Entity
@Table(name = "shop_ratings")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = ShopScopeFilter.NAME, condition = ShopScopeFilter.CONDITION)
@EntityListeners(TenantEntityListener.class)
public class ShopRating implements ShopOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_id")
    private Long shopId;

    @Override
    public Long getShopId() {
        return shopId;
    }

    @Override
    public void setShopId(Long shopId) {
        this.shopId = shopId;
    }

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "rating", nullable = false)
    private Integer rating;

    @Column(name = "comment", length = 1000)
    private String comment;

    /**
     * The reason codes, as rows (§18).
     *
     * <p>An @ElementCollection rather than a joined string: "how many of my
     * three-star ratings say DELIVERED_LATE" is the question a shopkeeper
     * needs answered, and a comma-separated column cannot be grouped by.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "shop_rating_reasons",
            joinColumns = @JoinColumn(name = "shop_rating_id"))
    @Column(name = "reason", length = 40, nullable = false)
    @Enumerated(EnumType.STRING)
    private Set<ShopRatingReason> reasons = new LinkedHashSet<>();

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // --------------------------------------------- §21 one response each

    @Column(name = "merchant_response", length = 1000)
    private String merchantResponse;

    @Column(name = "merchant_response_at")
    private LocalDateTime merchantResponseAt;

    @Column(name = "merchant_response_by", length = 120)
    private String merchantResponseBy;

    @Column(name = "customer_reply", length = 1000)
    private String customerReply;

    @Column(name = "customer_reply_at")
    private LocalDateTime customerReplyAt;

    // ---------------------------------------------------- §20 hidden, not gone

    @Column(name = "hidden_at")
    private LocalDateTime hiddenAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "hidden_reason", length = 40)
    private HideReason hiddenReason;

    @Column(name = "hidden_by", length = 120)
    private String hiddenBy;

    // ------------------------------------------------------- §22 reporting

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

    /**
     * Whether the stars count towards the shop's average.
     *
     * <p>A HIDDEN RATING USUALLY STILL COUNTS - see {@link HideReason}. If it
     * did not, hiding would be worth doing for the arithmetic alone and §20
     * would be a formality.
     */
    public boolean countsTowardsTheAverage() {
        return hiddenAt == null || (hiddenReason != null && hiddenReason.stillCounts());
    }

    /** §22: flagged by the shop, and still shown. Reporting is not hiding. */
    public boolean isReported() {
        return reportedAt != null;
    }
}
