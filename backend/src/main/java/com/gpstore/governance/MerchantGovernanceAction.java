package com.gpstore.governance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One recorded step the platform took against a merchant (§2).
 *
 * <p>NOT SHOP-OWNED, DELIBERATELY. Every other row in this system that
 * concerns a merchant is scoped by the shop filter, and this one is not: it
 * is the PLATFORM's record ABOUT a merchant, a merchant may run several
 * shops, and an action about the merchant as a whole has no shop to belong
 * to. The merchant's own access is derived from the shop their credential
 * resolved to - see {@link MerchantGovernance} - rather than from a filter,
 * and that derivation is the only route a merchant has to these rows.
 *
 * <p>THE ROW IS THE TRANSPARENCY. §2 asks for a governance process a merchant
 * can see and answer; a status column can say SUSPENDED but cannot say why,
 * who, on what evidence, whether it was appealed, or what was decided.
 */
@Entity
@Table(name = "merchant_governance_actions")
@Getter
@Setter
@NoArgsConstructor
public class MerchantGovernanceAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /**
     * Which shop this is about, or null for the merchant as a whole.
     *
     * <p>NOT NAMED shop_id, in the column or here. Every other shop id in
     * this system is a tenancy boundary - filtered on read, stamped on write.
     * This one is data the platform reads and the merchant reads about
     * themselves; calling it shop_id would make it look like isolation while
     * providing none.
     */
    @Column(name = "about_shop_id")
    private Long aboutShopId;

    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, length = 20)
    private GovernanceLevel level;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 40)
    private GovernanceReason reasonCode;

    /** The evidence, in words the merchant reads. */
    @Column(name = "detail", length = 1000)
    private String detail;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt = LocalDateTime.now();

    @Column(name = "issued_by", nullable = false, length = 120)
    private String issuedBy;

    /** Warnings decay; null means it does not expire by itself. */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /** A reinstatement points at what it lifts. */
    @Column(name = "supersedes_id")
    private Long supersedesId;

    @Column(name = "appeal_text", length = 2000)
    private String appealText;

    @Column(name = "appealed_at")
    private LocalDateTime appealedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "appeal_outcome", length = 20)
    private AppealOutcome appealOutcome;

    @Column(name = "appeal_note", length = 1000)
    private String appealNote;

    @Column(name = "appeal_decided_at")
    private LocalDateTime appealDecidedAt;

    @Column(name = "appeal_decided_by", length = 120)
    private String appealDecidedBy;

    /**
     * Whether this action is still in force at a given moment.
     *
     * <p>THREE WAYS TO STOP COUNTING, and all three matter: it expired, it
     * was overturned on appeal, or something later superseded it. A ladder
     * where nothing ever comes off is one every long-lived merchant
     * eventually falls off.
     *
     * @param at the moment being asked about - the caller's clock, never this
     *           method's, so that a standing check and the decision it feeds
     *           cannot disagree about the time
     */
    public boolean isLiveAt(LocalDateTime at, boolean superseded) {
        if (appealOutcome == AppealOutcome.OVERTURNED || superseded) {
            return false;
        }
        if (level == GovernanceLevel.REINSTATEMENT) {
            return false;
        }
        return expiresAt == null || at == null || at.isBefore(expiresAt);
    }

    /** Awaiting a decision. */
    public boolean isAppealOpen() {
        return appealedAt != null && appealOutcome == null;
    }
}
