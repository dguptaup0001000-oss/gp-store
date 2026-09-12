package com.gpstore.platform;

import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ConflictException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.service.AuditLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Onboarding, suspending and removing a business.
 *
 * EVERY MOVE IS CHECKED AND RECORDED. The transition table lives on
 * {@link MerchantStatus}; this is where it is enforced, where the reason is
 * required, and where the audit line is written. §21: a marketplace that can
 * drop a shopkeeper without a recorded reason is not one a shopkeeper should
 * join.
 *
 * NOTHING IS DELETED (§91). REMOVED is a status. The orders, payments and
 * invoices under a merchant who has left are financial records that outlive
 * the relationship.
 *
 * AUTHORIZATION IS NOT HERE. It is on the route (see SecurityConfig:
 * /api/platform/** needs PERM_PLATFORM_ADMIN) so that a service reachable from
 * two places cannot be reached from the unguarded one - and so this file is
 * about the lifecycle rather than about who is calling.
 */
@Service
public class MerchantLifecycleService {

    private final MerchantRepository merchants;
    private final ShopRepository shops;
    private final AuditLogService auditLog;

    public MerchantLifecycleService(MerchantRepository merchants, ShopRepository shops,
                                    AuditLogService auditLog) {
        this.merchants = merchants;
        this.shops = shops;
        this.auditLog = auditLog;
    }

    @Transactional(readOnly = true)
    public List<Merchant> all() {
        return merchants.findAll();
    }

    @Transactional(readOnly = true)
    public Merchant byId(Long id) {
        return merchants.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Merchant not found"));
    }

    /**
     * Registers a business. It cannot trade yet.
     *
     * STARTS AT APPLICATION, never at ACTIVE, whoever is calling. A merchant
     * created straight into trading is a merchant nobody checked, and the
     * status column would then be recording a review that never happened.
     */
    @Transactional
    public Merchant register(String legalName, String displayName, String contactPhone,
                             String contactEmail, Long ownerCustomerId, boolean demo) {
        if (legalName == null || legalName.isBlank()) {
            throw new BadRequestException("A merchant needs a legal name.");
        }
        Merchant merchant = new Merchant();
        merchant.setLegalName(legalName.trim());
        merchant.setDisplayName(displayName == null || displayName.isBlank()
                ? legalName.trim() : displayName.trim());
        merchant.setContactPhone(blankToNull(contactPhone));
        merchant.setContactEmail(blankToNull(contactEmail));
        merchant.setOwnerCustomerId(ownerCustomerId);
        merchant.setStatus(MerchantStatus.APPLICATION);
        merchant.setIsDemo(demo);
        merchant.setActive(Boolean.TRUE);

        Merchant saved = merchants.save(merchant);
        auditLog.log("MERCHANT_REGISTERED", "Merchant", saved.getId(),
                "status=APPLICATION, demo=" + demo);
        return saved;
    }

    /**
     * Moves a merchant along its lifecycle.
     *
     * @param reason why - required, and stored. "Suspended" with no reason is
     *               a decision nobody can review or appeal.
     */
    @Transactional
    public Merchant transition(Long merchantId, MerchantStatus next, String reason) {
        Merchant merchant = byId(merchantId);
        MerchantStatus current = merchant.getStatus();

        if (current == next) {
            return merchant;
        }
        if (!current.canMoveTo(next)) {
            throw new ConflictException(
                    "A merchant cannot go from " + current + " to " + next
                            + ". Allowed from here: " + current.allowedNext());
        }
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException(
                    "A reason is required for every merchant status change.");
        }

        merchant.setStatus(next);
        merchant.setStatusReason(reason.trim());
        merchant.setUpdatedAt(LocalDateTime.now());
        if (next == MerchantStatus.REMOVED) {
            merchant.setActive(Boolean.FALSE);
            merchant.setDeletedAt(LocalDateTime.now());
        }

        Merchant saved = merchants.save(merchant);
        auditLog.log("MERCHANT_STATUS_CHANGED", "Merchant", saved.getId(),
                current + " -> " + next + ": " + reason.trim());

        // A business that has stopped stops its shops with it. Left alone,
        // a removed merchant's storefronts would go on taking orders that
        // nobody is answerable for.
        if (!next.canTrade()) {
            for (Shop shop : shops.findByMerchantId(saved.getId())) {
                if (shop.getStatus() == ShopStatus.ACTIVE || shop.getStatus() == ShopStatus.PAUSED) {
                    shop.setStatus(next == MerchantStatus.REMOVED
                            ? ShopStatus.CLOSED : ShopStatus.SUSPENDED);
                    shop.setStatusReason("Merchant " + next + ": " + reason.trim());
                    shops.save(shop);
                    auditLog.log("SHOP_STATUS_CHANGED", "Shop", shop.getId(),
                            "followed merchant to " + shop.getStatus());
                }
            }
        }
        return saved;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
