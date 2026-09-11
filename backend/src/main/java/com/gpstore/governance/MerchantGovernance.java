package com.gpstore.governance;

import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ConflictException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.platform.Merchant;
import com.gpstore.platform.MerchantRepository;
import com.gpstore.platform.MerchantStatus;
import com.gpstore.platform.Shop;
import com.gpstore.platform.ShopLifecycleService;
import com.gpstore.platform.ShopRepository;
import com.gpstore.platform.ShopStatus;
import com.gpstore.platform.TenantContext;
import com.gpstore.platform.TenantScope;
import com.gpstore.service.AuditLogService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The governance ladder: warn, warn finally, suspend, end - and appeal (§2).
 *
 * <p>WHAT THIS REPLACES. The platform could already set a merchant to
 * SUSPENDED. It could not say why in a form anybody could read afterwards,
 * could not do anything SHORT of suspension, and gave the merchant no way to
 * answer. §2 asks for a governance process rather than a switch, and a
 * process needs four things a status column cannot hold: a rung below the
 * top, a recorded reason, a rule against skipping rungs, and an appeal.
 *
 * <p>THE RULE AGAINST SKIPPING IS THE POINT. A suspension issued to a
 * merchant who was never warned is indistinguishable, from the shopkeeper's
 * side, from the arbitrary switch this replaces - so {@link #issue} refuses
 * it. The exception is deliberately narrow: three reason codes
 * ({@link GovernanceReason#allowsImmediateSuspension}) skip the ladder, and
 * all three are cases where letting trade continue hurts a customer or breaks
 * the law. Owing GP-STORE money is not one of them.
 *
 * <p>NOTHING HERE IS SHOP-SCOPED. These are platform records about a
 * merchant. A merchant reads their OWN through {@link #standingForShop} and
 * {@link #historyForShop}, which derive the merchant from the shop the
 * credential resolved to - never from anything a request carried (§78).
 */
@Service
public class MerchantGovernance {

    /**
     * How long a warning counts for.
     *
     * <p>Ninety days, and the number matters less than the fact that there is
     * one: a ladder with no way down is a ladder every long-lived merchant
     * eventually falls off, for a bad fortnight two years ago.
     */
    public static final int WARNING_DAYS = 90;

    /** A final warning is heavier, so it stands for longer. */
    public static final int FINAL_WARNING_DAYS = 180;

    private final MerchantGovernanceRepository actions;
    private final MerchantRepository merchants;
    private final ShopRepository shops;
    private final ShopLifecycleService shopLifecycle;
    private final AuditLogService auditLog;

    public MerchantGovernance(MerchantGovernanceRepository actions,
                              MerchantRepository merchants,
                              ShopRepository shops,
                              ShopLifecycleService shopLifecycle,
                              AuditLogService auditLog) {
        this.actions = actions;
        this.merchants = merchants;
        this.shops = shops;
        this.shopLifecycle = shopLifecycle;
        this.auditLog = auditLog;
    }

    // ------------------------------------------------------ issuing a step

    /**
     * Takes one step against a merchant, or refuses to.
     *
     * @param shopId  the one shop this is about, or null for the merchant
     * @param detail  the evidence, in words the merchant will read
     */
    @Transactional
    public MerchantGovernanceAction issue(Long merchantId, Long shopId, GovernanceLevel level,
                                          GovernanceReason reason, String detail, String actor) {

        if (level == null || reason == null) {
            throw new BadRequestException("A level and a reason are both required.");
        }
        if (level == GovernanceLevel.REINSTATEMENT) {
            throw new BadRequestException(
                    "A reinstatement lifts a specific action - use reinstate(actionId).");
        }
        Merchant merchant = merchants.findById(merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("No such merchant"));

        if (shopId != null) {
            Shop shop = TenantContext.runWithin(TenantScope.platform(),
                    () -> shops.findById(shopId).orElse(null));
            if (shop == null || !merchantId.equals(shop.getMerchantId())) {
                throw new BadRequestException(
                        "That shop does not belong to this merchant.");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        MerchantStanding standing = standing(merchantId, now);

        if (!standing.supports(level) && !reason.allowsImmediateSuspension()) {
            GovernanceLevel needed = level.below();
            throw new ConflictException(
                    "A " + level + " cannot be issued before a " + needed + ". §2 makes the "
                            + "ladder the thing that keeps the top of it legitimate; "
                            + reason + " is not one of the reasons that may skip it.");
        }

        MerchantGovernanceAction action = new MerchantGovernanceAction();
        action.setMerchantId(merchantId);
        action.setAboutShopId(shopId);
        action.setLevel(level);
        action.setReasonCode(reason);
        action.setDetail(trimmed(detail, 1000));
        action.setIssuedAt(now);
        action.setIssuedBy(actor);
        action.setExpiresAt(expiryFor(level, now));

        MerchantGovernanceAction saved = actions.save(action);

        if (level.stopsTrading()) {
            stopTrading(merchant, shopId, level, reason, actor);
        }

        auditLog.log("MERCHANT_" + level, "Merchant", merchantId,
                reason + " by " + actor + (detail == null ? "" : ": " + detail));
        return saved;
    }

    private static LocalDateTime expiryFor(GovernanceLevel level, LocalDateTime now) {
        return switch (level) {
            case WARNING -> now.plusDays(WARNING_DAYS);
            case FINAL_WARNING -> now.plusDays(FINAL_WARNING_DAYS);
            // A SUSPENSION does not lift itself. Somebody decides it is over,
            // and that decision is its own recorded action.
            case SUSPENSION, TERMINATION, REINSTATEMENT -> null;
        };
    }

    /**
     * Makes a suspension actually mean something.
     *
     * <p>THE RECORD AND THE EFFECT ARE SEPARATE, AND BOTH ARE NEEDED. Writing
     * the row without changing the status would leave a "suspended" merchant
     * still taking orders; changing the status without the row is the
     * arbitrary switch this class exists to replace.
     *
     * <p>THE ORDERS ALREADY ON THE BOARD ARE NOT TOUCHED, for exactly the
     * reason §12 gives about a shop closing: an order the shop accepted is
     * still owed, and the platform cancelling a customer's shopping as a side
     * effect of disciplining a merchant would be the customer paying for it.
     */
    private void stopTrading(Merchant merchant, Long shopId, GovernanceLevel level,
                             GovernanceReason reason, String actor) {
        String why = level + ": " + reason + " (by " + actor + ")";
        if (shopId != null) {
            shopLifecycle.transitionAsPlatform(shopId, ShopStatus.SUSPENDED, why);
            return;
        }
        merchant.setStatus(MerchantStatus.SUSPENDED);
        merchants.save(merchant);
        for (Shop shop : TenantContext.runWithin(TenantScope.platform(),
                () -> shops.findByMerchantId(merchant.getId()))) {
            if (shop.getStatus() != ShopStatus.SUSPENDED && shop.getStatus() != ShopStatus.CLOSED) {
                shopLifecycle.transitionAsPlatform(shop.getId(), ShopStatus.SUSPENDED, why);
            }
        }
    }

    // -------------------------------------------------------- lifting one

    /**
     * Lifts an action, and records the lifting as its own step.
     *
     * <p>A REINSTATEMENT IS A ROW, not a deletion. "Suspended, then
     * reinstated" is one story a merchant and a reviewer can both read;
     * removing the suspension would leave a gap where the story was.
     */
    @Transactional
    public MerchantGovernanceAction reinstate(Long actionId, String note, String actor) {
        MerchantGovernanceAction original = actions.findById(actionId)
                .orElseThrow(() -> new ResourceNotFoundException("No such action"));

        MerchantGovernanceAction lifting = new MerchantGovernanceAction();
        lifting.setMerchantId(original.getMerchantId());
        lifting.setAboutShopId(original.getAboutShopId());
        lifting.setLevel(GovernanceLevel.REINSTATEMENT);
        lifting.setReasonCode(original.getReasonCode());
        lifting.setDetail(trimmed(note, 1000));
        lifting.setIssuedAt(LocalDateTime.now());
        lifting.setIssuedBy(actor);
        lifting.setSupersedesId(original.getId());
        MerchantGovernanceAction saved = actions.save(lifting);

        if (original.getLevel().stopsTrading()) {
            resumeTrading(original, actor);
        }

        auditLog.log("MERCHANT_REINSTATED", "Merchant", original.getMerchantId(),
                "lifting action " + actionId + " by " + actor
                        + (note == null ? "" : ": " + note));
        return saved;
    }

    private void resumeTrading(MerchantGovernanceAction original, String actor) {
        String why = "Reinstated by " + actor;
        if (original.getAboutShopId() != null) {
            shopLifecycle.transitionAsPlatform(original.getAboutShopId(), ShopStatus.ACTIVE, why);
            return;
        }
        merchants.findById(original.getMerchantId()).ifPresent(merchant -> {
            merchant.setStatus(MerchantStatus.ACTIVE);
            merchants.save(merchant);
            for (Shop shop : TenantContext.runWithin(TenantScope.platform(),
                    () -> shops.findByMerchantId(merchant.getId()))) {
                if (shop.getStatus() == ShopStatus.SUSPENDED) {
                    shopLifecycle.transitionAsPlatform(shop.getId(), ShopStatus.ACTIVE, why);
                }
            }
        });
    }

    // ------------------------------------------------------------ appeals

    /**
     * The merchant answers back, once per action (§2).
     *
     * <p>ONCE, because a merchant who could re-appeal indefinitely could keep
     * a suspension permanently "under review" and a reviewer permanently
     * busy. If new evidence appears, a reviewer can reopen it - that is a
     * decision somebody makes, not one the appellant makes for them.
     */
    @Transactional
    public MerchantGovernanceAction appeal(Long actionId, Long merchantId, String text) {
        String written = trimmed(text, 2000);
        if (written == null) {
            throw new BadRequestException("Say why you think this was wrong.");
        }
        MerchantGovernanceAction action = actions.findById(actionId)
                .orElseThrow(() -> new ResourceNotFoundException("No such action"));
        if (!action.getMerchantId().equals(merchantId)) {
            // The same 404 an unknown id gives: a merchant must not be able to
            // discover that an action against somebody else exists.
            throw new ResourceNotFoundException("No such action");
        }
        if (action.getAppealedAt() != null) {
            throw new ConflictException("You have already appealed this. One appeal each.");
        }
        action.setAppealText(written);
        action.setAppealedAt(LocalDateTime.now());
        MerchantGovernanceAction saved = actions.save(action);
        auditLog.log("MERCHANT_APPEALED", "Merchant", merchantId,
                "appealed action " + actionId);
        return saved;
    }

    /** A platform reviewer decides. OVERTURNED lifts, REDUCED drops a rung. */
    @Transactional
    public MerchantGovernanceAction decideAppeal(Long actionId, AppealOutcome outcome,
                                                 String note, String actor) {
        if (outcome == null) {
            throw new BadRequestException("An appeal is decided UPHELD, REDUCED or OVERTURNED.");
        }
        MerchantGovernanceAction action = actions.findById(actionId)
                .orElseThrow(() -> new ResourceNotFoundException("No such action"));
        if (action.getAppealedAt() == null) {
            throw new ConflictException("This action has not been appealed.");
        }
        if (action.getAppealOutcome() != null) {
            throw new ConflictException("This appeal has already been decided.");
        }

        action.setAppealOutcome(outcome);
        action.setAppealNote(trimmed(note, 1000));
        action.setAppealDecidedAt(LocalDateTime.now());
        action.setAppealDecidedBy(actor);

        if (outcome == AppealOutcome.REDUCED) {
            GovernanceLevel reduced = action.getLevel().below();
            if (reduced != null) {
                action.setLevel(reduced);
                action.setExpiresAt(expiryFor(reduced, LocalDateTime.now()));
            }
        }

        MerchantGovernanceAction saved = actions.save(action);

        // Both outcomes that stop the action counting have to undo its
        // effect, or a merchant wins an appeal and stays shut.
        boolean noLongerStopsTrading = outcome == AppealOutcome.OVERTURNED
                || (outcome == AppealOutcome.REDUCED && !saved.getLevel().stopsTrading());
        if (noLongerStopsTrading) {
            resumeTrading(saved, actor);
        }

        auditLog.log("MERCHANT_APPEAL_" + outcome, "Merchant", saved.getMerchantId(),
                "action " + actionId + " by " + actor + (note == null ? "" : ": " + note));
        return saved;
    }

    // ----------------------------------------------------------- reading it

    /** Where this merchant stands right now. */
    @Transactional(readOnly = true)
    public MerchantStanding standing(Long merchantId) {
        return standing(merchantId, LocalDateTime.now());
    }

    @Transactional(readOnly = true)
    public MerchantStanding standing(Long merchantId, LocalDateTime at) {
        List<MerchantGovernanceAction> history =
                actions.findByMerchantIdOrderByIssuedAtDesc(merchantId);
        if (history.isEmpty()) {
            return MerchantStanding.clean(merchantId, at);
        }

        Set<Long> superseded = new HashSet<>();
        for (MerchantGovernanceAction action : history) {
            if (action.getSupersedesId() != null) {
                superseded.add(action.getSupersedesId());
            }
        }

        GovernanceLevel highest = null;
        int live = 0;
        List<Long> openAppeals = new ArrayList<>();
        for (MerchantGovernanceAction action : history) {
            if (action.isAppealOpen()) {
                openAppeals.add(action.getId());
            }
            if (!action.isLiveAt(at, superseded.contains(action.getId()))) {
                continue;
            }
            live++;
            if (action.getLevel().isAbove(highest)) {
                highest = action.getLevel();
            }
        }

        boolean mayTrade = highest == null || !highest.stopsTrading();
        return new MerchantStanding(merchantId, highest, live, mayTrade,
                List.copyOf(openAppeals), at);
    }

    /** Everything on this merchant's record, newest first. */
    @Transactional(readOnly = true)
    public List<MerchantGovernanceAction> historyFor(Long merchantId) {
        return actions.findByMerchantIdOrderByIssuedAtDesc(merchantId);
    }

    /**
     * The merchant's own view, derived from the shop in scope.
     *
     * <p>NOT FROM A MERCHANT ID IN THE REQUEST. A merchant id a caller could
     * name is a merchant id a caller could change, and this is the one
     * surface where reading somebody else's row would mean reading their
     * disciplinary record.
     */
    @Transactional(readOnly = true)
    public MerchantStanding standingForShop() {
        Long merchantId = merchantOfShopInScope();
        return merchantId == null
                ? MerchantStanding.clean(null, LocalDateTime.now())
                : standing(merchantId);
    }

    @Transactional(readOnly = true)
    public List<MerchantGovernanceAction> historyForShop() {
        Long merchantId = merchantOfShopInScope();
        return merchantId == null ? List.of() : historyFor(merchantId);
    }

    /** The merchant behind the credential, or null for a platform caller. */
    public Long merchantOfShopInScope() {
        TenantScope scope = TenantContext.current();
        if (scope == null || scope.isPlatform() || scope.shopId() == null) {
            return null;
        }
        Long shopId = scope.shopId();
        return TenantContext.runWithin(TenantScope.platform(),
                () -> shops.findById(shopId).map(Shop::getMerchantId).orElse(null));
    }

    @Transactional(readOnly = true)
    public Page<MerchantGovernanceAction> openAppeals(Pageable pageable) {
        return actions.findByAppealedAtIsNotNullAndAppealOutcomeIsNullOrderByAppealedAtAsc(pageable);
    }

    @Transactional(readOnly = true)
    public Page<MerchantGovernanceAction> everything(Pageable pageable) {
        return actions.findAllByOrderByIssuedAtDesc(pageable);
    }

    private static String trimmed(String raw, int max) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() <= max ? t : t.substring(0, max);
    }
}
