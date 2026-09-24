package com.gpstore.platform.api;

import com.gpstore.catalog.shop.ShopProductVariant;
import com.gpstore.catalog.shop.ShopProductVariantRepository;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.platform.*;
import com.gpstore.platform.shopinfo.ShopPolicy;
import com.gpstore.platform.shopinfo.ShopPolicyKind;
import com.gpstore.security.AdminPermission;
import com.gpstore.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * A shopkeeper's own shop: its profile, its price list, and who is on its staff.
 *
 * THERE IS NO SHOP ID IN ANY PATH HERE, and that is the design rather than an
 * omission. Every route acts on "the shop this request is for", which
 * TenantContextFilter established from the credential before the controller
 * ran. A /api/shops/{id}/listings shape would put a shop id in front of a
 * caller, and then the only thing standing between a merchant and a
 * competitor's price list would be a check somebody has to remember to write
 * on every route.
 *
 * WHAT A SHOPKEEPER MAY CHANGE HERE. Their own listing rows: price, cost,
 * whether the item is on their shelf, their own shelf order. What they may NOT
 * change from here is what the product IS - its name, pack size, category,
 * photo - because that row is shared with every other shop selling it. That
 * lives under /api/products and is gated by CatalogDefinitionAuthorization.
 */
@RestController
@RequestMapping("/api/shop")
public class ShopSelfServiceController {

    private final ShopRepository shops;
    private final ShopLifecycleService shopLifecycle;
    private final ShopProductVariantRepository listings;
    private final ShopStaffRepository staff;
    private final CurrentUser currentUser;
    private final ShopMembership membership;
    private final MerchantRepository merchants;
    private final com.gpstore.repository.CustomerRepository customers;
    private final com.gpstore.service.AuditLogService auditLog;
    private final com.gpstore.money.ShopEarnings earnings;
    private final ShopReadiness readiness;
    private final com.gpstore.catalog.shop.ShopShelfCache shelfCache;
    private final com.gpstore.platform.shopinfo.ShopPolicyRepository policies;
    private final com.gpstore.platform.ShopReliability reliabilityService;
    private final com.gpstore.payment.collection.PaymentCollection paymentCollection;
    private final com.gpstore.repository.InventoryRepository inventory;
    private final com.gpstore.repository.ProductVariantRepository variants;
    private final com.gpstore.service.ProductService products;
    private final com.gpstore.catalog.shop.ShopVariantEditing variantEditing;
    private final com.gpstore.engagement.ListingEngagement engagement;
    private final com.gpstore.catalog.shop.ShopCatalogueBrowse catalogueBrowse;
    private final com.gpstore.catalog.shop.CategoryFinder categoryFinder;
    private final com.gpstore.catalog.shop.ShopProductEditing productEditing;
    private final com.gpstore.catalog.shop.ShopCategoryService shopCategories;
    private final com.gpstore.service.VariantImageService variantImages;

    public ShopSelfServiceController(ShopRepository shops, ShopLifecycleService shopLifecycle,
                                     ShopProductVariantRepository listings,
                                     ShopStaffRepository staff, CurrentUser currentUser,
                                     ShopMembership membership, MerchantRepository merchants,
                                     com.gpstore.repository.CustomerRepository customers,
                                     com.gpstore.service.AuditLogService auditLog,
                                     com.gpstore.money.ShopEarnings earnings,
                                     ShopReadiness readiness,
                                     com.gpstore.catalog.shop.ShopShelfCache shelfCache,
                                     com.gpstore.platform.shopinfo.ShopPolicyRepository policies,
                                     com.gpstore.platform.ShopReliability reliabilityService,
                                     com.gpstore.payment.collection.PaymentCollection paymentCollection,
                                     com.gpstore.repository.InventoryRepository inventory,
                                     com.gpstore.repository.ProductVariantRepository variants,
                                     com.gpstore.service.ProductService products,
                                     com.gpstore.catalog.shop.ShopVariantEditing variantEditing,
                                     com.gpstore.engagement.ListingEngagement engagement,
                                     com.gpstore.catalog.shop.ShopCatalogueBrowse catalogueBrowse,
                                     com.gpstore.catalog.shop.CategoryFinder categoryFinder,
                                     com.gpstore.catalog.shop.ShopProductEditing productEditing,
                                     com.gpstore.catalog.shop.ShopCategoryService shopCategories,
                                     com.gpstore.service.VariantImageService variantImages) {
        this.variantEditing = variantEditing;
        this.engagement = engagement;
        this.catalogueBrowse = catalogueBrowse;
        this.categoryFinder = categoryFinder;
        this.productEditing = productEditing;
        this.shopCategories = shopCategories;
        this.variantImages = variantImages;
        this.products = products;
        this.paymentCollection = paymentCollection;
        this.inventory = inventory;
        this.variants = variants;
        this.shelfCache = shelfCache;
        this.policies = policies;
        this.reliabilityService = reliabilityService;
        this.earnings = earnings;
        this.readiness = readiness;
        this.membership = membership;
        this.merchants = merchants;
        this.customers = customers;
        this.auditLog = auditLog;
        this.shops = shops;
        this.shopLifecycle = shopLifecycle;
        this.listings = listings;
        this.currentUser = currentUser;
        this.staff = staff;
    }

    public record ShopProfile(Long id, String code, String displayName, ShopStatus status,
                              String statusReason, Double latitude, Double longitude,
                              BigDecimal maxDeliveryRadiusKm, String timeZone,
                              String supportPhone, String supportEmail, String supportWhatsapp,
                              String logoUrl, String businessName, String gstin,
                              String fssaiLicence,
                              // READ-ONLY HERE. The shopkeeper sees what the
                              // platform has confirmed about them; they cannot
                              // set it - see updateProfile, which has no branch
                              // that touches it (§10).
                              com.gpstore.platform.ShopVerificationLevel verificationLevel,
                              java.time.LocalDateTime verifiedAt) {
        static ShopProfile of(Shop s) {
            return new ShopProfile(s.getId(), s.getCode(), s.getDisplayName(), s.getStatus(),
                    s.getStatusReason(), s.getLatitude(), s.getLongitude(),
                    s.getMaxDeliveryRadiusKm(), s.getTimeZone(),
                    s.getSupportPhone(), s.getSupportEmail(), s.getSupportWhatsapp(),
                    s.getLogoUrl(), s.getBusinessName(), s.getGstin(), s.getFssaiLicence(),
                    s.getVerificationLevel(), s.getVerifiedAt());
        }
    }

    public record ProfileUpdate(String displayName, String supportPhone, String supportEmail,
                                String supportWhatsapp, Double latitude, Double longitude,
                                BigDecimal maxDeliveryRadiusKm, String timeZone,
                                String logoUrl, String businessName, String gstin,
                                String fssaiLicence) {}

    public record PauseRequest(String status, String reason) {}

    public record ListingView(Long id, Long productVariantId, BigDecimal sellingPrice,
                              BigDecimal costPrice, BigDecimal mrp, Boolean available,
                              Boolean active, Integer displayOrder,
                              com.gpstore.catalog.shop.CommerceMode commerceMode) {
        static ListingView of(ShopProductVariant l) {
            return new ListingView(l.getId(), l.getProductVariantId(), l.getSellingPrice(),
                    l.getCostPrice(), l.getMrp(), l.getAvailable(), l.getActive(),
                    l.getDisplayOrder(), l.getCommerceMode());
        }
    }

    public record ListingUpdate(BigDecimal sellingPrice, BigDecimal costPrice, BigDecimal mrp,
                                Boolean available, Boolean active, Integer displayOrder,
                                String commerceMode) {}

    /**
     * What a shopkeeper says is on the shelf.
     *
     * <p>COUNTED STOCK ONLY. There is deliberately no reservedStock here: that
     * number belongs to baskets and orders in flight, and a shopkeeper doing a
     * stock-take is counting sacks of atta, not carts. Letting this route set
     * it would let a correction quietly release stock somebody has already
     * paid for.
     */
    public record StockUpdate(Integer stock, Integer minimumStock, Integer maximumStock) {}

    public record StockView(Long productVariantId, Integer stock, Integer reservedStock,
                            Integer availableStock, Integer minimumStock, Integer maximumStock) {}

    /** The shop this request is for. Which shop that is came from the credential. */
    @GetMapping("/profile")
    public ShopProfile profile() {
        return ShopProfile.of(currentShop());
    }

    /**
     * The BUSINESS behind the shops, which is not the same thing as a shop.
     *
     * WHY THIS IS SEPARATE (§12, §63). A merchant's identity - who owns it,
     * what the platform has verified, whether the account is in good standing
     * - belongs to the merchant and not to any one of its storefronts.
     * Folding it into the shop profile would mean a business with three
     * kiranas answering the question "who are you" three times and possibly
     * differently, and would put merchant-level standing on a screen that
     * shop staff open all day.
     *
     * ONLY THE OWNER. §12 says merchant-level information must not leak to
     * shop staff, and the check is ownership of the merchant rather than a
     * permission, deliberately: an ORDER_MANAGER at one of the shops holds
     * plenty of shop permissions and has no business reading the merchant's
     * standing with the platform. The owner is the account the merchant row
     * names, which nothing a client sends can change.
     *
     * NO SECRETS. There is no password here and no activation code - those
     * are shown once when they are created and stored only as hashes, so
     * there is nothing for this route to return even if it wanted to.
     */
    @GetMapping("/merchant")
    public MerchantProfile merchantProfile() {
        Shop shop = currentShop();
        Long merchantId = shop.getMerchantId();
        com.gpstore.platform.Merchant merchant = merchantId == null ? null
                : merchants.findById(merchantId).orElse(null);
        if (merchant == null) {
            throw new com.gpstore.exception.ResourceNotFoundException(
                    "This shop is not attached to a merchant.");
        }
        Long me = currentUser.customerId();
        if (me == null || !me.equals(merchant.getOwnerCustomerId())) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Only the merchant's owner may see the business account.");
        }
        List<com.gpstore.platform.Shop> theirs = shops.findByMerchantId(merchant.getId());
        return new MerchantProfile(
                merchant.getId(),
                com.gpstore.platform.PublicIds.merchant(merchant.getId()),
                merchant.getLegalName(), merchant.getDisplayName(),
                merchant.getContactName(), merchant.getContactEmail(), merchant.getContactPhone(),
                merchant.getStatus(), merchant.getStatusReason(),
                merchant.getCreatedAt(), theirs.size());
    }

    /**
     * A merchant, as its own owner sees it.
     *
     * Shop count rather than the shops themselves: which shops exist is
     * already answered, with the switcher's own view of status and operability,
     * by /api/shop/my-shops. Two lists of the same thing drift.
     */
    public record MerchantProfile(Long id, String merchantRef,
                                  String legalName, String displayName,
                                  String contactName, String contactEmail, String contactPhone,
                                  com.gpstore.platform.MerchantStatus status, String statusReason,
                                  java.time.LocalDateTime createdAt, int shopCount) {}

    /**
     * Every shop this account may work in, and which one it is working in now.
     *
     * <p>WHAT THE SWITCHER NEEDS, and the missing half of §4. One merchant
     * owning several shops was already true in the data and already enforced
     * on the way in - TenantResolver.select accepts an X-Shop-Id naming any
     * shop the credential permits, and refuses every other - but nothing told
     * the app WHICH shops those were, so a merchant with three kiranas had no
     * way to reach the second and third.
     *
     * <p>THIS IS NOT AN AUTHORIZATION, it is a list of ones already granted.
     * The ids come from this account's staff rows; sending one back as
     * X-Shop-Id narrows to a shop it already permits and can never widen
     * (§13). A shop the account is not staff of is not in this list and is
     * refused if named anyway.
     *
     * <p>It also answers the case that used to be a hard error: an account on
     * two rosters with no default could not resolve a shop at all, because
     * choosing one for them would have been choosing one merchant's data over
     * another's. Now the app can ask, show them both, and let them pick.
     */
    @GetMapping("/my-shops")
    public MyShops myShops() {
        Long accountId = currentUser.customerId();
        List<Long> permitted = membership.shopIdsFor(accountId);
        Long active = TenantContext.current() == null ? null : TenantContext.current().shopId();

        List<ShopChoice> choices = permitted.stream()
                .map(shops::findById)
                .flatMap(java.util.Optional::stream)
                .map(shop -> new ShopChoice(shop.getId(), shop.getCode(), shop.getDisplayName(),
                        shop.getStatus(), shop.getLogoUrl(),
                        // OPERABLE, not merely listed: a shop that is closed,
                        // or whose merchant has been removed, has nothing left
                        // to administer, and offering it in a switcher would
                        // be offering a screen that errors on arrival.
                        membership.isOperable(shop.getId()),
                        shop.getId().equals(active)))
                .toList();

        return new MyShops(choices, active);
    }

    /**
     * @param shops  every shop this account is on the staff list of
     * @param acting the one this request was scoped to, so a switcher can show
     *               which is selected without guessing
     */
    public record MyShops(List<ShopChoice> shops, Long acting) {}

    public record ShopChoice(Long shopId, String code, String displayName, ShopStatus status,
                             String logoUrl, boolean operable, boolean acting) {}

    @PutMapping("/profile")
    public ShopProfile updateProfile(@RequestBody ProfileUpdate update) {
        requirePermission(AdminPermission.CATALOG_MANAGE);
        Shop shop = currentShop();

        // Status is NOT here. A shop pauses itself through /status below,
        // which goes through the transition table; letting a profile PUT carry
        // a status would be a second, unchecked way to change one.
        if (update.displayName() != null && !update.displayName().isBlank()) {
            shop.setDisplayName(update.displayName().trim());
        }
        if (update.supportPhone() != null) shop.setSupportPhone(blankToNull(update.supportPhone()));
        if (update.supportEmail() != null) shop.setSupportEmail(blankToNull(update.supportEmail()));
        if (update.supportWhatsapp() != null) shop.setSupportWhatsapp(blankToNull(update.supportWhatsapp()));
        if (update.latitude() != null) shop.setLatitude(update.latitude());
        if (update.longitude() != null) shop.setLongitude(update.longitude());
        if (update.maxDeliveryRadiusKm() != null) shop.setMaxDeliveryRadiusKm(update.maxDeliveryRadiusKm());
        if (update.timeZone() != null && !update.timeZone().isBlank()) {
            shop.setTimeZone(update.timeZone().trim());
        }
        if (update.logoUrl() != null) shop.setLogoUrl(blankToNull(update.logoUrl()));
        if (update.businessName() != null) shop.setBusinessName(blankToNull(update.businessName()));
        if (update.gstin() != null) shop.setGstin(blankToNull(update.gstin()));
        if (update.fssaiLicence() != null) shop.setFssaiLicence(blankToNull(update.fssaiLicence()));

        // VERIFICATION IS NOT HERE, and its absence is the feature. A merchant
        // who could set their own verification level has been verified by
        // nobody; the badge is the platform's to grant (§10), through
        // PlatformMerchantController and nowhere else. Business details ARE
        // here - a shopkeeper states them, and the platform checks them.
        return ShopProfile.of(shops.save(shop));
    }

    // --------------------------------------------------- what the shop promises

    /** This shop's policies, in the shopkeeper's own words. */
    @GetMapping("/policies")
    public List<PolicyView> policies() {
        return policies.findAllByOrderByKindAsc().stream().map(PolicyView::of).toList();
    }

    /**
     * Writes one policy.
     *
     * <p>An empty body REMOVES it rather than storing an empty promise: a
     * heading with nothing under it reads to a customer as a policy they
     * cannot find, which is worse than a shop that has not written one.
     */
    @PutMapping("/policies/{kind}")
    public List<PolicyView> setPolicy(@PathVariable String kind, @RequestBody PolicyUpdate update) {
        requirePermission(AdminPermission.CATALOG_MANAGE);
        ShopPolicyKind parsed;
        try {
            parsed = ShopPolicyKind.valueOf(kind.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BadRequestException("Unknown policy: " + kind
                    + ". This shop can state its DELIVERY, CANCELLATION and RETURNS policies.");
        }

        ShopPolicy row = policies.findByKind(parsed.name()).orElse(null);
        if (update.body() == null || update.body().isBlank()) {
            if (row != null) {
                policies.delete(row);
                auditLog.log("SHOP_POLICY_REMOVED", "ShopPolicy", row.getId(), parsed.name());
            }
            return policies();
        }

        if (row == null) {
            row = new ShopPolicy();
            row.setKind(parsed);
        }
        row.setBody(update.body().trim());
        row.setUpdatedBy("shop:" + currentUser.customerId());
        ShopPolicy saved = policies.save(row);
        auditLog.log("SHOP_POLICY_SET", "ShopPolicy", saved.getId(), parsed.name());
        return policies();
    }

    /**
     * This shop's own trading record, and what stands between it and TRUSTED.
     *
     * <p>THE MERCHANT'S OWN, and only their own - it is computed for the shop
     * in scope. A badge a shopkeeper cannot find out how to earn is a badge
     * that looks bought, so the answer includes why it is not showing.
     */
    @GetMapping("/reliability")
    public com.gpstore.platform.ShopReliability.Record reliability() {
        return reliabilityService.forCurrentShop();
    }

    /**
     * Where this shop's online money actually goes.
     *
     * <p>SAID OUT LOUD, because it is currently GP-STORE's account and a
     * shopkeeper is entitled to know that rather than to infer it from an
     * earnings screen that says "awaiting collection". Decision W1 already
     * says a merchant's product proceeds are the merchant's, so this endpoint
     * is a known gap reported honestly rather than an open question put to
     * the shopkeeper - see PaymentCollectionModel. §17 keeps the seam behind
     * it provider-agnostic until the provider, onboarding/KYC, settlement,
     * fee and refund decisions are made.
     */
    @GetMapping("/payment-collection")
    public com.gpstore.payment.collection.PaymentCollection.Collector paymentCollection() {
        return paymentCollection.forShop(currentShop().getId());
    }

    public record PolicyUpdate(String body) {}

    public record PolicyView(String kind, String body, java.time.LocalDateTime updatedAt) {
        static PolicyView of(ShopPolicy policy) {
            return new PolicyView(policy.getKindName(), policy.getBody(), policy.getUpdatedAt());
        }
    }

    /**
     * The shopkeeper's own pause and reopen.
     *
     * Refuses anything else - lifting a platform suspension, or closing for
     * good - through ShopLifecycleService, so the rule holds whatever route
     * reaches it.
     */
    @PutMapping("/status")
    public ShopProfile setStatus(@RequestBody PauseRequest request) {
        requirePermission(AdminPermission.CATALOG_MANAGE);
        ShopStatus next;
        try {
            next = ShopStatus.valueOf(String.valueOf(request.status()).trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw new BadRequestException("Unknown shop status: " + request.status());
        }
        return ShopProfile.of(shopLifecycle.transitionAsMerchant(
                currentShop().getId(), next, request.reason()));
    }

    // ------------------------------------------------- putting something new on

    /**
     * A merchant adds something they sell.
     *
     * <p>THE ROUTE THAT DID NOT EXIST, and its absence is the whole bug. A
     * marketplace merchant had exactly two catalogue powers:
     * {@code PUT /api/shop/listings/{variantId}}, which prices a variant that
     * ALREADY exists centrally, and {@code POST /api/products}, which is the
     * platform's catalogue and answers 403 to anyone without CATALOG_DEFINE.
     * Neither one lets a phone shop introduce a phone nobody has sold here
     * before. There was no third route.
     *
     * <p>WHY IT LOOKED LIKE IT WORKED ON A REAL DEVICE. Production still runs
     * with {@code platform.mode} unset, which parses to SINGLE_SHOP, and in
     * that mode CatalogDefinitionAuthorization falls back to CATALOG_MANAGE -
     * which every shopkeeper holds. So Add Product reached
     * {@code POST /api/products} after all, wrote a central catalogue row with
     * no variant and no listing, and answered 200. The merchant's Products
     * screen asks the shelf ({@code findAllListedForCurrentShop}), a catalogue
     * row with no variant can never satisfy it, and so the product was
     * invisible to its own creator from the moment it was written - with no
     * error anywhere to explain it.
     *
     * <p>THIS IS THE SHOPKEEPER'S ACT, SO IT TAKES THE SHOPKEEPER'S PERMISSION
     * (CATALOG_MANAGE) and is scoped to the acting shop. It writes the
     * catalogue entry, the first variant, THIS shop's listing and THIS shop's
     * opening stock in one transaction. Defining shared taxonomy stays a
     * platform act on the other route; adding stock to your own shelf never was
     * one.
     */
    @PostMapping("/products")
    public com.gpstore.dto.response.ProductResponse addProduct(
            @jakarta.validation.Valid @RequestBody
            com.gpstore.dto.request.ProductCreateRequest request) {
        return products.createProduct(request);
    }

    // --------------------------------------------------------- the price list

    @GetMapping("/listings")
    public List<ListingView> listings(@RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "100") int size) {
        return listings.findAllByOrderByIdAsc(com.gpstore.config.PageRequests.of(page, size))
                .map(ListingView::of).toList();
    }

    /**
     * This shop's shelf, filtered by how the items are sold.
     *
     * <p>WHAT {@code /listings} COULD NOT DO. That route returns prices and
     * ids - no product name, no image, no commerce mode - so a screen showing
     * "everything you sell as Visit to Buy" would have had to fetch each
     * product separately. This reads the listing and its catalogue row in one
     * statement and filters by mode in the database.
     *
     * @param mode comma-separated commerce modes. Absent means all three.
     * @param q    optional free text over product name, brand and category.
     */
    @GetMapping("/catalogue")
    public com.gpstore.catalog.shop.ShopCatalogueBrowse.CataloguePage catalogue(
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        requirePermission(AdminPermission.CATALOG_VIEW);
        return catalogueBrowse.page(commerceModes(mode), q, page, size);
    }

    /** How many listings this shop has in each mode, for the nav badges. */
    @GetMapping("/catalogue/counts")
    public java.util.Map<String, Long> catalogueCounts() {
        requirePermission(AdminPermission.CATALOG_VIEW);
        return catalogueBrowse.countsByMode();
    }

    /**
     * Categories for a picker, most relevant to THIS shop first.
     *
     * <p>The picker was the whole catalogue in id order, which is thirty rows
     * on a kirana and several thousand on a marketplace. A phone merchant
     * should not scroll past Atta and Baby Care to reach Mobile Phones.
     */
    @GetMapping("/category-search")
    public java.util.List<com.gpstore.catalog.shop.CategoryFinder.CategoryOption> categorySearch(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "40") int limit) {
        requirePermission(AdminPermission.CATALOG_VIEW);
        return categoryFinder.search(q, limit);
    }

    /** The direct children of a category, for drilling into a parent. */
    @GetMapping("/category-search/{parentId}/children")
    public java.util.List<com.gpstore.catalog.shop.CategoryFinder.CategoryOption> categoryChildren(
            @PathVariable Long parentId) {
        requirePermission(AdminPermission.CATALOG_VIEW);
        return categoryFinder.childrenOf(parentId);
    }

    /**
     * Reads the mode filter, refusing to guess.
     *
     * <p>An unrecognised mode yields an empty set, which the browse treats as
     * "all modes" - widening on a typo is safe here because this is a
     * merchant reading their OWN shelf, where every mode is theirs to see.
     * The customer-facing feed deliberately narrows instead, because there
     * widening would put a Visit-to-Buy card on a screen that draws ADD.
     */
    private static java.util.Set<com.gpstore.catalog.shop.CommerceMode> commerceModes(String raw) {
        if (raw == null || raw.isBlank()) {
            return java.util.Set.of();
        }
        java.util.Set<com.gpstore.catalog.shop.CommerceMode> modes =
                new java.util.LinkedHashSet<>();
        for (String piece : raw.split(",")) {
            try {
                modes.add(com.gpstore.catalog.shop.CommerceMode
                        .valueOf(piece.trim().toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException unknown) {
                // Ignored - see the method comment.
            }
        }
        return modes;
    }

    /**
     * A new shelf row needs an explicit mode. An edit that omits the field may
     * keep its already-persisted mode, but never invents Buy Online.
     */
    private static com.gpstore.catalog.shop.CommerceMode requireCommerceMode(
            String requested, com.gpstore.catalog.shop.CommerceMode existing) {
        if (requested == null || requested.isBlank()) {
            if (existing != null) return existing;
            throw new BadRequestException("Choose how customers obtain this item.");
        }
        try {
            return com.gpstore.catalog.shop.CommerceMode.valueOf(
                    requested.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BadRequestException("Unsupported commerce mode: " + requested);
        }
    }

    /**
     * Sets what this shop charges for one catalogue item.
     *
     * THE SHOP IS NOT A PARAMETER. The row is found through the shop-scoped
     * repository and stamped by the tenant listener on insert, so this method
     * has no way to write into another shop even if a caller wanted it to.
     */
    @PutMapping("/listings/{productVariantId}")
    public ListingView upsertListing(@PathVariable Long productVariantId,
                                     @RequestBody ListingUpdate update) {
        if (update.sellingPrice() == null || update.sellingPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("A listing needs a selling price greater than 0.");
        }
        ShopProductVariant listing = listings.findByProductVariantId(productVariantId)
                .orElseGet(ShopProductVariant::new);
        listing.setCommerceMode(requireCommerceMode(update.commerceMode(), listing.getCommerceMode()));
        listing.setProductVariantId(productVariantId);
        listing.setSellingPrice(update.sellingPrice());
        listing.setCostPrice(update.costPrice());
        listing.setMrp(update.mrp());
        listing.setAvailable(update.available() == null ? Boolean.TRUE : update.available());
        listing.setActive(update.active() == null ? Boolean.TRUE : update.active());
        listing.setDisplayOrder(update.displayOrder());
        ListingView saved = ListingView.of(listings.save(listing));
        // The customer app was showing the old price until the cache TTL
        // drained - see ShopShelfCache. A price screen whose changes do not
        // reach the storefront is decoration.
        shelfCache.changed();
        return saved;
    }

    // ------------------------------------------------- the shopkeeper's product

    /**
     * Edit Product → Save Changes, for a product THIS shop sells.
     *
     * <p>THE LAST ROUTE STILL POINTING AT THE PLATFORM. Variants, photos and
     * departments moved to the merchant's own surface; the product screen did
     * not, so Save Changes went to {@code PUT /api/products/{id}} and answered
     * 403 - while the very same screen had just rendered the product, because
     * the merchant's shelf genuinely lists it.
     *
     * <p>Active applies to this shop's listings. Name, brand and category are
     * catalogue-wide and are accepted only while this shop is the only one
     * selling the product - see ShopProductEditing.
     */
    @PutMapping("/products/{productId}")
    public com.gpstore.catalog.shop.ShopProductEditing.ProductView saveProduct(
            @PathVariable Long productId,
            @RequestBody com.gpstore.catalog.shop.ShopProductEditing.ProductEdit edit) {
        requirePermission(AdminPermission.CATALOG_MANAGE);
        return productEditing.update(productId, edit);
    }

    /** One product as THIS shop sells it, and whether its catalogue half is editable. */
    @GetMapping("/products/{productId}")
    public com.gpstore.catalog.shop.ShopProductEditing.ProductView readProduct(
            @PathVariable Long productId) {
        requirePermission(AdminPermission.CATALOG_MANAGE);
        return productEditing.view(productId);
    }

    // ------------------------------------------------- the shopkeeper's variant

    /**
     * Saves an item on THIS shop's shelf.
     *
     * <p>THE ROUTE A REAL DEVICE NEEDED AND DID NOT HAVE. The merchant admin
     * app saved variants through {@code PUT /api/product-variants/{id}} - the
     * platform catalogue route, which needs CATALOG_DEFINE once a second shop
     * exists. A merchant repricing his own phone got 403 and the app turned it
     * into "Couldn't save variant - please check the values and try again".
     * Nothing was ever wrong with 35000/30000/29000.
     *
     * <p>THE SHOP IS NOT A PARAMETER. The listing is read through the
     * shop-scoped repository, so naming another shop's variant finds nothing -
     * 404, not 403, so the ids other shops use stay undiscoverable.
     */
    @PutMapping("/variants/{productVariantId}")
    public com.gpstore.catalog.shop.ShopVariantEditing.VariantView saveVariant(
            @PathVariable Long productVariantId,
            @RequestBody com.gpstore.catalog.shop.ShopVariantEditing.VariantEdit edit) {
        requirePermission(AdminPermission.CATALOG_MANAGE);
        return variantEditing.update(productVariantId, edit);
    }

    /**
     * A SECOND variant of something this shop already sells.
     *
     * <p>8/128 and 12/256 of the same phone; red and green of the same saree.
     * The merchant had no route to the second one at all - the only create was
     * the platform's catalogue, which a shopkeeper cannot reach once a second
     * merchant is trading.
     */
    @PostMapping("/products/{productId}/variants")
    public com.gpstore.catalog.shop.ShopVariantEditing.VariantView addVariant(
            @PathVariable Long productId,
            @RequestBody com.gpstore.catalog.shop.ShopVariantEditing.VariantEdit edit) {
        requirePermission(AdminPermission.CATALOG_MANAGE);
        return variantEditing.create(productId, edit);
    }

    /** One of this shop's variants, with its attributes and this shop's price. */
    @GetMapping("/variants/{productVariantId}")
    public com.gpstore.catalog.shop.ShopVariantEditing.VariantView readVariant(
            @PathVariable Long productVariantId) {
        requirePermission(AdminPermission.CATALOG_MANAGE);
        return variantEditing.view(productVariantId);
    }

    /**
     * This variant's photos, replaced with exactly this list in this order.
     *
     * <p>The same whole-list contract as the platform route, and the same
     * five-photo limit - but reachable by the shopkeeper whose shelf it is,
     * and only for a variant THIS shop lists. Photo saving failed on a real
     * device for exactly the reason the price save did: the only route was the
     * platform's.
     */
    @PutMapping("/variants/{productVariantId}/images")
    public List<String> saveVariantImages(
            @PathVariable Long productVariantId,
            @RequestBody VariantImagesRequest request) {
        requirePermission(AdminPermission.CATALOG_MANAGE);
        // Ownership first: the listing read is shop-scoped, so a variant this
        // shop does not sell is a 404 before any photo row is touched.
        variantEditing.listingFor(productVariantId).orElseThrow(
                () -> new ResourceNotFoundException("This shop does not list that item."));
        return variantImages.replaceImages(productVariantId, request.imageUrls()).stream()
                .map(com.gpstore.upload.CatalogImageDelivery::forClient)
                .toList();
    }

    /** This variant's photos, read, for a variant this shop lists. */
    @GetMapping("/variants/{productVariantId}/images")
    public List<String> readVariantImages(@PathVariable Long productVariantId) {
        requirePermission(AdminPermission.CATALOG_MANAGE);
        variantEditing.listingFor(productVariantId).orElseThrow(
                () -> new ResourceNotFoundException("This shop does not list that item."));
        return variantImages.imagesFor(productVariantId).stream()
                .map(com.gpstore.upload.CatalogImageDelivery::forClient)
                .toList();
    }

    public record VariantImagesRequest(List<String> imageUrls) {
    }

    // ------------------------------------------------ the shop's own departments

    /**
     * The departments belonging to this shop.
     *
     * <p>NOT the platform taxonomy. Creating one here adds a row this shop
     * owns; no other merchant sees it and no customer's category tree grows.
     */
    @GetMapping("/categories")
    public List<com.gpstore.catalog.shop.ShopCategoryService.ShopCategoryView> myCategories() {
        requirePermission(AdminPermission.CATALOG_MANAGE);
        return shopCategories.mine();
    }

    @PostMapping("/categories")
    public com.gpstore.catalog.shop.ShopCategoryService.ShopCategoryView addCategory(
            @RequestBody com.gpstore.catalog.shop.ShopCategoryService.ShopCategoryRequest request) {
        requirePermission(AdminPermission.CATALOG_MANAGE);
        return shopCategories.create(request);
    }

    @PutMapping("/categories/{id}")
    public com.gpstore.catalog.shop.ShopCategoryService.ShopCategoryView renameCategory(
            @PathVariable Long id,
            @RequestBody com.gpstore.catalog.shop.ShopCategoryService.ShopCategoryRequest request) {
        requirePermission(AdminPermission.CATALOG_MANAGE);
        return shopCategories.update(id, request);
    }

    @DeleteMapping("/categories/{id}")
    public void removeCategory(@PathVariable Long id) {
        requirePermission(AdminPermission.CATALOG_MANAGE);
        shopCategories.remove(id);
    }

    @DeleteMapping("/listings/{productVariantId}")
    public void delistItem(@PathVariable Long productVariantId) {
        ShopProductVariant listing = listings.findByProductVariantId(productVariantId)
                .orElseThrow(() -> new ResourceNotFoundException("This shop does not list that item"));
        listing.setAvailable(Boolean.FALSE);
        listing.setActive(Boolean.FALSE);
        listings.save(listing);
        shelfCache.changed();
    }

    /**
     * How much of a listed item this shop actually has.
     *
     * <p>THE STEP THAT HAD NO ROUTE. A shop could set its price here and could
     * not put stock behind it: /api/inventory takes a raw Inventory entity and
     * needs the stock row's own id, which a brand-new listing does not have
     * yet. The result was that the only way to open a shelf was to write to
     * the inventory table directly - which is what the second-merchant
     * onboarding test did, with a jdbc INSERT, because nothing else worked.
     * A flow that cannot be completed through the API is not an onboarding
     * flow, so this is the missing half of {@link #upsertListing}.
     *
     * <p>REFUSES STOCK FOR SOMETHING THIS SHOP DOES NOT SELL. The listing is
     * looked up first, through the shop-scoped repository. Without that, a
     * shop could create inventory rows for catalogue items it has never
     * listed - rows that no storefront would ever show and no stock-take would
     * ever reconcile.
     *
     * <p>THE SHOP IS NOT A PARAMETER, exactly as in {@link #upsertListing}.
     * The row is read through the shop-scoped repository and stamped by the
     * tenant listener on insert, so two shops holding stock of the same
     * catalogue variant get two rows and neither can see the other's -
     * inventory is unique on (shop_id, product_variant_id), not on the variant
     * alone (V48).
     */
    @PutMapping("/listings/{productVariantId}/stock")
    public StockView setStock(@PathVariable Long productVariantId,
                              @RequestBody StockUpdate update) {
        requirePermission(AdminPermission.INVENTORY_MANAGE);
        if (update.stock() == null || update.stock() < 0) {
            throw new BadRequestException("Stock cannot be negative, and has to be given.");
        }
        listings.findByProductVariantId(productVariantId).orElseThrow(
                () -> new ResourceNotFoundException(
                        "This shop does not list that item. Put it on the shelf with a price "
                                + "first, then say how much of it there is."));

        com.gpstore.entity.Inventory row = inventory.findByProductVariantId(productVariantId)
                .orElseGet(() -> {
                    com.gpstore.entity.Inventory fresh = new com.gpstore.entity.Inventory();
                    fresh.setProductVariant(variants.getReferenceById(productVariantId));
                    // NOT NULL: a new row has nothing reserved, and leaving it
                    // null would make availableStock() arithmetic on a null.
                    fresh.setReservedStock(0);
                    return fresh;
                });

        Integer wasStock = row.getStock();
        row.setStock(update.stock());
        if (update.minimumStock() != null) {
            row.setMinimumStock(update.minimumStock());
        }
        if (update.maximumStock() != null) {
            row.setMaximumStock(update.maximumStock());
        }
        // reservedStock is deliberately untouched - see StockUpdate.

        com.gpstore.entity.Inventory saved = inventory.save(row);
        auditLog.log("SHOP_STOCK_SET", "Inventory", saved.getId(),
                "variant=" + productVariantId + ", " + wasStock + " -> " + update.stock());
        // The storefront caches what is on the shelf, and a shelf that still
        // says "out of stock" after a delivery arrived is the same bug the
        // price path had.
        shelfCache.changed();

        int reserved = saved.getReservedStock() == null ? 0 : saved.getReservedStock();
        int stock = saved.getStock() == null ? 0 : saved.getStock();
        return new StockView(productVariantId, saved.getStock(), saved.getReservedStock(),
                Math.max(0, stock - reserved), saved.getMinimumStock(), saved.getMaximumStock());
    }

    /** What this shop has on the shelf for one item it lists. */
    @GetMapping("/listings/{productVariantId}/stock")
    public StockView stock(@PathVariable Long productVariantId) {
        requirePermission(AdminPermission.INVENTORY_MANAGE);
        listings.findByProductVariantId(productVariantId).orElseThrow(
                () -> new ResourceNotFoundException("This shop does not list that item"));
        com.gpstore.entity.Inventory row = inventory.findByProductVariantId(productVariantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No stock has been recorded for that item yet."));
        int reserved = row.getReservedStock() == null ? 0 : row.getReservedStock();
        int stock = row.getStock() == null ? 0 : row.getStock();
        return new StockView(productVariantId, row.getStock(), row.getReservedStock(),
                Math.max(0, stock - reserved), row.getMinimumStock(), row.getMaximumStock());
    }

    /**
     * What still stands between this shop and its first order.
     *
     * CATALOG_VIEW, the same as the rest of the read surface - this is the
     * screen a shopkeeper opens when nothing is selling and they want to know
     * why. It reports; ShopTradingGate still decides.
     *
     * Never shown to a customer. The customer's version of this answer is
     * deliberately one sentence with no detail in it.
     */
    @GetMapping("/readiness")
    public ShopReadiness.Readiness readiness() {
        return readiness.forCurrentShop();
    }

    // ------------------------------------------------------------ the money

    /**
     * What this shop took, and in what form.
     *
     * ANALYTICS_VIEW, deliberately - the same permission that opens the
     * dashboard, because this is the dashboard's money half rather than a
     * separate privilege. A shopkeeper who may see how many orders they had
     * may see what those orders were worth.
     *
     * NO SHOP ID, again. The statement is about the shop the credential
     * resolved to, so there is no id for anyone to change - and no route that
     * would answer with somebody else's takings if they did.
     */
    @GetMapping("/earnings")
    public com.gpstore.money.ShopEarnings.Statement earnings(
            @RequestParam(defaultValue = "30") int days) {
        requirePermission(AdminPermission.ANALYTICS_VIEW);
        return earnings.forCurrentShop(days);
    }

    /** What is waiting to be done, by order status, for this shop only. */
    @GetMapping("/open-work")
    public java.util.Map<String, Long> openWork() {
        requirePermission(AdminPermission.ORDERS_VIEW);
        return earnings.openWorkForCurrentShop();
    }

    // -------------------------------------------------------------- the staff

    public record StaffView(Long customerId, Boolean isDefault, Boolean active) {}

    /** Who works here. Shop-scoped, so it can only ever be this shop's list. */
    @GetMapping("/staff")
    public List<StaffView> staff() {
        return staff.findAll().stream()
                .map(s -> new StaffView(s.getCustomerId(), s.getIsDefault(), s.getActive()))
                .toList();
    }

    /**
     * Adds somebody to THIS shop's staff.
     *
     * NO SHOP ID IN THE PATH, like everything else here - the shop is the one
     * the credential resolved to, so a shopkeeper cannot add staff to a
     * competitor's roster by changing a number. The limits on WHO may be added
     * are in ShopMembership.addToOwnShop, where they belong: they are the same
     * limits whatever route reaches them.
     *
     * CUSTOMERS_MANAGE, not CATALOG_MANAGE. Hiring is a people decision, and
     * the role that stocks the shelves has no business making it.
     */
    @PostMapping("/staff")
    public StaffView addStaff(@RequestBody AddStaffRequest request) {
        requirePermission(AdminPermission.CUSTOMERS_MANAGE);
        Long shopId = TenantContext.require().requireShopId();
        ShopStaff added = membership.addToOwnShop(shopId, request.customerId(),
                accountId -> customers.findById(accountId)
                        .map(c -> c.getRole() == null ? null : c.getRole().name())
                        .orElse(null));
        auditLog.log("SHOP_STAFF_ADDED", "ShopStaff", added.getId(),
                "shop=" + shopId + ", account=" + request.customerId());
        return new StaffView(added.getCustomerId(), added.getIsDefault(), added.getActive());
    }

    /**
     * Takes somebody off this shop's staff.
     *
     * DEACTIVATES RATHER THAN DELETES (§91) - who worked here and when is part
     * of the record behind every order they touched. And a shop cannot remove
     * the merchant's own owner, because an owner who could be removed by their
     * own manager is a shop that can be locked away from the person
     * answerable for it.
     */
    @DeleteMapping("/staff/{customerId}")
    public void removeStaff(@PathVariable Long customerId) {
        requirePermission(AdminPermission.CUSTOMERS_MANAGE);
        Shop shop = currentShop();
        Long owner = merchants.findById(shop.getMerchantId())
                .map(com.gpstore.platform.Merchant::getOwnerCustomerId)
                .orElse(null);
        if (customerId != null && customerId.equals(owner)) {
            throw new com.gpstore.exception.ConflictException(
                    "The merchant's owner cannot be removed from their own shop's staff.");
        }
        membership.revoke(shop.getId(), customerId);
        auditLog.log("SHOP_STAFF_REMOVED", "ShopStaff", shop.getId(),
                "account=" + customerId);
    }

    public record AddStaffRequest(Long customerId) {}

    private Shop currentShop() {
        Long shopId = TenantContext.require().requireShopId();
        return shops.findById(shopId)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found"));
    }

    /**
     * A second check beside the route's.
     *
     * /api/shop/** is gated on CATALOG_VIEW so that read routes are open to
     * every staff role that can see the catalogue. The write routes need more,
     * and asking here keeps the two rules in the file that knows which is
     * which rather than in a path pattern nobody re-reads.
     */
    /**
     * How this shop's offline listings are doing.
     *
     * <p>THE ONLY HONEST ANSWER GP-STORE HAS. An online sale records itself -
     * an order, a payment, a receipt - and the rest of this dashboard counts
     * them. A Visit-to-Buy listing has none of that: the customer sees the
     * card, taps Directions, walks in and pays in cash. So what comes back
     * here is interest, and the report carries its own sentence saying that
     * these are not sales, because a number without it invites exactly the
     * reading that would make it a lie.
     *
     * <p>SCOPED BY TenantContext like every other route on this controller:
     * the shop is not a parameter, so a merchant cannot ask about another
     * shop's listings by changing an id.
     */
    @GetMapping("/engagement")
    public com.gpstore.engagement.ListingEngagement.EngagementReport listingEngagement(
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            java.time.LocalDateTime from,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            java.time.LocalDateTime to) {
        requirePermission(AdminPermission.CATALOG_MANAGE);
        Long shopId = TenantContext.current() == null ? null : TenantContext.current().shopId();
        java.time.LocalDateTime end = to == null ? java.time.LocalDateTime.now() : to;
        // A month, because that is the window a shopkeeper thinks in and the
        // one a shorter default would keep making them widen by hand.
        java.time.LocalDateTime start = from == null ? end.minusDays(30) : from;
        return engagement.reportFor(shopId, start, end);
    }

    private void requirePermission(AdminPermission permission) {
        if (!currentUser.has(permission)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "This account may not change the shop's " + permission.name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
