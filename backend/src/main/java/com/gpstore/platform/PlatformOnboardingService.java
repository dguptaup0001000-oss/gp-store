package com.gpstore.platform;

import com.gpstore.entity.Role;
import com.gpstore.exception.BadRequestException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Onboards a merchant in one call: login, business, review, shop.
 *
 * WHY THIS EXISTS RATHER THAN SIX CALLS FROM THE PHONE. Opening a merchant by
 * hand means opening a staff login, registering the business, walking it
 * PENDING_REVIEW then APPROVED, and opening a shop under it. Done from the
 * client that is five round trips with four places to stop halfway, and every
 * one of them leaves something real behind: a login nobody can use, a business
 * stuck in APPLICATION, a merchant with no shop. Those half-states are not
 * theoretical - they are what the platform owner kept finding.
 *
 * One transaction, so the answer is a merchant who can be handed their
 * password, or nothing at all.
 *
 * WHAT IT DELIBERATELY DOES NOT DO: let them trade. The shop it opens has
 * nothing on its shelves, and ShopReadiness counts listings and stock as
 * blocking for exactly that reason. Switching a shop to ACTIVE here would put
 * an empty storefront in front of customers - findable, and with nothing to
 * sell. The merchant signs in, puts stock up, and the platform owner throws
 * the two trade switches when there is something to buy.
 *
 * IT DOES NOT INVENT A REVIEW EITHER. The lifecycle walk is recorded with the
 * reason it actually had: the platform owner onboarded this merchant directly.
 * That is a true sentence in the audit log, where "papers verified" would not
 * be.
 */
@Service
public class PlatformOnboardingService {

    /**
     * Recorded against both transitions.
     *
     * NOT "papers checked". A one-screen onboarding is the platform owner
     * vouching for somebody they dealt with, and the audit log should say so
     * rather than imply a review that nobody performed. §21 asks for a reason
     * on every status change; it is worth no less when the answer is quick.
     */
    static final String REASON = "Onboarded directly by the platform owner";

    /** Shop codes are varchar(40); leave room for the -2, -3 suffix below. */
    private static final int CODE_LIMIT = 36;

    private final PlatformStaffService staff;
    private final MerchantLifecycleService merchants;
    private final ShopLifecycleService shops;
    private final ShopRepository shopRepository;

    public PlatformOnboardingService(PlatformStaffService staff,
                                     MerchantLifecycleService merchants,
                                     ShopLifecycleService shops,
                                     ShopRepository shopRepository) {
        this.staff = staff;
        this.merchants = merchants;
        this.shops = shops;
        this.shopRepository = shopRepository;
    }

    /**
     * @param oneTimePassword the ONLY copy. Not stored, not recoverable.
     */
    public record OnboardedMerchant(Long merchantId,
                                    String businessName,
                                    Long shopId,
                                    String shopCode,
                                    Long ownerCustomerId,
                                    String ownerEmail,
                                    String oneTimePassword) {}

    @Transactional
    public OnboardedMerchant onboard(String businessName,
                                     String ownerName,
                                     String ownerEmail,
                                     String ownerPhone,
                                     String shopCode,
                                     Double latitude,
                                     Double longitude,
                                     BigDecimal maxDeliveryRadiusKm,
                                     String timeZone,
                                     boolean demo) {

        String business = require(businessName, "The business needs a name");
        String owner = require(ownerName, "The owner needs a name");
        String email = require(ownerEmail, "An email is the owner's login, so it is required");

        // REQUIRED HERE, THOUGH openShop ITSELF ALLOWS THEM TO BE NULL - and
        // the difference is the point of this endpoint. A shop with no pin
        // matches no customer, and one with no radius is offered to nobody:
        // ShopReadiness calls both blocking. Opening a shop without them
        // produces something that looks finished and can never sell, which is
        // the trap this flow exists to close.
        if (latitude == null || longitude == null) {
            throw new BadRequestException(
                    "Where is the shop? The marketplace matches customers to shops by "
                            + "distance, so a shop with no location is offered to nobody.");
        }
        if (maxDeliveryRadiusKm == null || maxDeliveryRadiusKm.signum() <= 0) {
            throw new BadRequestException(
                    "How far will this shop deliver? A shop that has not said is offered "
                            + "to nobody.");
        }

        // The login first: the merchant row wants its id, and an unused staff
        // account is the cheapest thing to be left holding if anything below
        // refuses. In one transaction nothing is left holding anything, but
        // the ordering still reads correctly.
        PlatformStaffService.OpenedAccount account =
                staff.openAccount(owner, email, ownerPhone, Role.ADMIN);

        Merchant merchant = merchants.register(
                business, null, ownerPhone, email, account.customerId(), demo);

        merchants.transition(merchant.getId(), MerchantStatus.PENDING_REVIEW, REASON);
        merchants.transition(merchant.getId(), MerchantStatus.APPROVED, REASON);

        String code = (shopCode == null || shopCode.isBlank())
                ? availableCodeFor(business)
                : shopCode.trim();

        Shop shop = shops.open(merchant.getId(), code, null,
                latitude, longitude, maxDeliveryRadiusKm, blankToNull(timeZone));

        return new OnboardedMerchant(
                merchant.getId(), merchant.getDisplayName(),
                shop.getId(), shop.getCode(),
                account.customerId(), account.email(), account.oneTimePassword());
    }

    /**
     * A code derived from the business name, and free.
     *
     * ASKING FOR ONE WOULD BE ASKING FOR A DECISION NOBODY HAS. A shop code is
     * an identifier this platform uses; a shopkeeper has no opinion about it
     * and no way to know which are taken. Derived, then de-duplicated, and
     * returned so the console can show what it became.
     */
    String availableCodeFor(String businessName) {
        String base = slug(businessName);
        if (shopRepository.findByCode(base).isEmpty()) {
            return base;
        }
        for (int suffix = 2; suffix < 100; suffix++) {
            String candidate = base + "-" + suffix;
            if (shopRepository.findByCode(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new BadRequestException(
                "Too many shops are already using a code like '" + base
                        + "'. Give this one a code of its own.");
    }

    /** Lowercase, hyphen-separated, ASCII. Never empty: a name of pure punctuation still needs a code. */
    static String slug(String businessName) {
        String cleaned = businessName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (cleaned.isBlank()) {
            cleaned = "shop";
        }
        return cleaned.length() > CODE_LIMIT ? cleaned.substring(0, CODE_LIMIT) : cleaned;
    }

    private static String require(String value, String complaint) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(complaint + ".");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
