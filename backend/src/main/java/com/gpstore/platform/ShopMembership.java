package com.gpstore.platform;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Which shops an account may work in, and which one it lands in by default.
 *
 * THE ANSWER COMES FROM THE DATABASE ON EVERY REQUEST. Not from a token
 * claim: a manager moved between shops, or removed from one, must stop being
 * able to work there NOW rather than when their token happens to expire.
 * Permissions in this codebase already work exactly this way, and the reason
 * is the same one.
 *
 * NOTHING HERE TAKES A SHOP FROM A CALLER. Every method takes the account id
 * from the verified credential and answers from rows. The one method that
 * accepts a shop id, {@link #permits}, is a check rather than a grant - it can
 * only ever say no to something the caller asked for.
 */
@Service
public class ShopMembership {

    private final ShopStaffRepository staff;
    private final ShopRepository shops;
    private final MerchantRepository merchants;

    public ShopMembership(ShopStaffRepository staff, ShopRepository shops, MerchantRepository merchants) {
        this.staff = staff;
        this.shops = shops;
        this.merchants = merchants;
    }

    /** Every shop this account may work in, default first. Empty for a customer. */
    @Transactional(readOnly = true)
    public List<Long> shopIdsFor(Long customerId) {
        return customerId == null ? List.of() : staff.shopIdsFor(customerId);
    }

    /**
     * The shop a request lands in when it names none.
     *
     * The explicit default if there is one, otherwise the only membership,
     * otherwise nothing - an account with three shops and no default has to
     * say which, because picking one for them is picking one merchant's data
     * over another's.
     */
    @Transactional(readOnly = true)
    public Optional<Long> defaultShopIdFor(Long customerId) {
        if (customerId == null) {
            return Optional.empty();
        }
        Optional<Long> explicit = staff.defaultShopIdFor(customerId);
        if (explicit.isPresent()) {
            return explicit;
        }
        List<Long> all = staff.shopIdsFor(customerId);
        return all.size() == 1 ? Optional.of(all.get(0)) : Optional.empty();
    }

    /**
     * May this account work in that shop?
     *
     * A CHECK, NEVER A GRANT. The shop id here is the one a caller asked for,
     * and the only thing this method can do with it is refuse.
     */
    @Transactional(readOnly = true)
    public boolean permits(Long customerId, Long shopId) {
        return shopId != null && shopIdsFor(customerId).contains(shopId);
    }

    /**
     * Whether a shop is in a state its staff may act in at all.
     *
     * A SUSPENDED SHOP IS NOT A DELETED ONE. Its staff can still sign in and
     * see why - being suspended and being unable to find out are different
     * punishments. What they cannot do is trade, which is enforced where
     * orders are placed rather than here.
     *
     * A REMOVED shop, or one whose MERCHANT cannot trade, is closed to its
     * staff outright: there is no shop left to administer.
     */
    @Transactional(readOnly = true)
    public boolean isOperable(Long shopId) {
        Optional<Shop> shop = shops.findById(shopId);
        if (shop.isEmpty() || shop.get().getStatus() == ShopStatus.CLOSED) {
            return false;
        }
        if (!Boolean.TRUE.equals(shop.get().getActive())) {
            return false;
        }
        Long merchantId = shop.get().getMerchantId();
        if (merchantId == null) {
            return false;
        }
        return merchants.findById(merchantId)
                .map(m -> m.getStatus() != MerchantStatus.REMOVED
                        && m.getStatus() != MerchantStatus.REJECTED
                        && Boolean.TRUE.equals(m.getActive()))
                .orElse(false);
    }

    /**
     * A shop adding somebody to its OWN staff, within the limits the platform
     * sets.
     *
     * WHY THIS IS NOT JUST grant(). "Who may work here" is the hinge the whole
     * isolation model turns on: a staff row is what gives an account a tenant
     * scope, so a shop that could add any account could hand itself a person
     * who already works for a competitor - or, worse, add a competitor's owner
     * and read nothing while giving that owner a second shop to select.
     *
     * THE LIMITS, and each is here because of what it prevents:
     *
     *   the account must not already be staff of a DIFFERENT shop - otherwise
     *   one merchant can attach themselves to another's roster by getting
     *   their own account added, and shop-switching does the rest;
     *
     *   the account must be a real, active staff account - a customer added to
     *   a roster would silently gain a shop scope they never applied for;
     *
     *   and a platform administrator can never be added, because their scope
     *   spans the marketplace and a shop must not be able to reach for it.
     *
     * Anything wider than this stays with the platform (PlatformMerchantController).
     */
    @Transactional
    public ShopStaff addToOwnShop(Long shopId, Long customerId,
                                  java.util.function.Function<Long, String> roleOf) {
        if (shopId == null || customerId == null) {
            throw new com.gpstore.exception.BadRequestException("A shop and an account are required.");
        }
        String role = roleOf.apply(customerId);
        if (role == null) {
            throw new com.gpstore.exception.ResourceNotFoundException("That account does not exist.");
        }
        if (com.gpstore.entity.Role.PLATFORM_ADMIN.name().equals(role)) {
            throw new com.gpstore.exception.ConflictException(
                    "A platform administrator cannot be added to a shop's staff.");
        }
        if (com.gpstore.security.RolePermissions.forRoleName(role).isEmpty()) {
            throw new com.gpstore.exception.ConflictException(
                    "That account is not a staff account, so it cannot be given a shop to work in.");
        }
        for (Long existing : shopIdsFor(customerId)) {
            if (!existing.equals(shopId)) {
                throw new com.gpstore.exception.ConflictException(
                        "That account already works at another shop.");
            }
        }
        return grant(shopId, customerId, true);
    }

    /** Adds an account to a shop's staff, idempotently. */
    @Transactional
    public ShopStaff grant(Long shopId, Long customerId, boolean asDefault) {
        ShopStaff membership = staff.findByShopIdAndCustomerId(shopId, customerId)
                .orElseGet(ShopStaff::new);
        membership.setShopId(shopId);
        membership.setCustomerId(customerId);
        membership.setActive(Boolean.TRUE);
        if (asDefault && defaultShopIdFor(customerId).isEmpty()) {
            membership.setIsDefault(Boolean.TRUE);
        }
        return staff.save(membership);
    }

    /**
     * Ends a membership.
     *
     * DEACTIVATES RATHER THAN DELETES (§91): who worked where and when is part
     * of the record behind every order they touched.
     */
    @Transactional
    public void revoke(Long shopId, Long customerId) {
        staff.findByShopIdAndCustomerId(shopId, customerId).ifPresent(membership -> {
            membership.setActive(Boolean.FALSE);
            membership.setIsDefault(Boolean.FALSE);
            staff.save(membership);
        });
    }
}
