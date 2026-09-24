package com.gpstore.service;

import com.gpstore.entity.Category;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.entity.Product;
import com.gpstore.exception.BadRequestException;
import com.gpstore.repository.ProductBrowseRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.dto.response.BestsellerTileResponse;
import com.gpstore.dto.response.ProductResponse;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final com.gpstore.repository.ProductImageRepository productImageRepository;
    private final ProductBrowseRepository productBrowseRepository;

    private final com.gpstore.repository.CategoryRepository categoryRepository;

    private final com.gpstore.catalog.shop.ShopPricedCatalogue shopPricedCatalogue;
    private final com.gpstore.catalog.shop.ShopStock shopStock;
    private final com.gpstore.platform.PlatformProperties platform;
    private final com.gpstore.platform.ShopRepository shops;
    private final com.gpstore.repository.ProductVariantRepository productVariants;
    private final com.gpstore.catalog.shop.ShopCatalog shopCatalog;
    private final InventoryService inventory;

    /**
     * WHY THIS CLASS GAINED A LOGGER. A merchant tapped Create Product, got a
     * 200, and found an empty list - and there was nothing in any log to say
     * what had happened, because from the server's point of view nothing had
     * gone wrong. Shelf creation now says what it wrote and what it refused, so
     * the next report of "I added it and it is not there" can be answered from
     * the logs instead of a device.
     *
     * NAMES AND IDS ONLY. No prices beyond the fact one was set, no customer
     * data, nothing from a token.
     */
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ProductService.class);

    public ProductService(
            ProductRepository productRepository,
            ProductBrowseRepository productBrowseRepository,
            com.gpstore.repository.ProductImageRepository productImageRepository,
            com.gpstore.repository.CategoryRepository categoryRepository,
            com.gpstore.catalog.shop.ShopPricedCatalogue shopPricedCatalogue,
            com.gpstore.catalog.shop.ShopStock shopStock,
            com.gpstore.platform.PlatformProperties platform,
            com.gpstore.platform.ShopRepository shops,
            com.gpstore.repository.ProductVariantRepository productVariants,
            com.gpstore.catalog.shop.ShopCatalog shopCatalog,
            InventoryService inventory) {
        this.productVariants = productVariants;
        this.shopCatalog = shopCatalog;
        this.inventory = inventory;
        this.shopStock = shopStock;
        this.shops = shops;
        this.productRepository = productRepository;
        this.productImageRepository = productImageRepository;
        this.productBrowseRepository = productBrowseRepository;
        this.categoryRepository = categoryRepository;
        this.shopPricedCatalogue = shopPricedCatalogue;
        this.platform = platform;
    }

    /**
     * Whether a product has to be on THIS shop's shelf to be shown.
     *
     * Under one shop it does not: the catalogue and the shelf are the same
     * thing, and requiring a listing would hide any variant that was priced
     * without being listed - a live product disappearing from a working shop
     * (§12). Under a marketplace it does: a storefront shows what that shop
     * sells.
     */
    private boolean requireListing() {
        return platform.getMode().isMultiShop();
    }

    /**
     * Whether the caller is one merchant among several, so their Products
     * screen shows their shelf rather than the whole catalogue.
     *
     * <p>EXTRACTED SO CREATE AND LIST CANNOT DISAGREE, which is the shape the
     * bug had. getAllForAdmin already asked this question before filtering by
     * shelf; createProduct did not ask it at all, so it happily wrote a
     * catalogue row the very next list call would hide. One predicate, both
     * callers.
     *
     * <p>THE SHOP COUNT IS PART OF IT, not just the configured mode. Production
     * still runs with {@code platform.mode} unset - SINGLE_SHOP - while more
     * than one merchant is trading, so a mode-only test would call today's real
     * marketplace a single shop and hide nothing.
     */
    private boolean sellsAsOneOfManyShops(com.gpstore.platform.TenantScope scope) {
        return scope != null
                && scope.isSingleShop()
                && (platform.getMode().isMultiShop() || shops.countByDeletedAtIsNull() > 1);
    }

    /**
     * Swaps the client's {"id": N} stub for the real Category row.
     *
     * THE ADMIN APP SHOWED "Something went wrong" ON PRODUCTS IT HAD JUST
     * CREATED SUCCESSFULLY, and this is why. The request body carries only
     * the category id, so Jackson builds a Category with that id and NULL for
     * every other field. Hibernate needs nothing more - it writes category_id
     * and the row is correct - but ProductResponse.from() then mapped that
     * same stub straight back out:
     *
     *     "category":{"id":1,"name":null,"description":null,...}
     *
     * Flutter's Category model declares `required String name`, so
     * Category.fromJson threw on the null before the screen ever saw a
     * product. The catch reported the only thing it had, "Something went
     * wrong. Please try again.", the admin retried, and the retry created a
     * SECOND product - which is how the live catalogue ended up with two
     * "machar bati" rows and two spellings of "pooja bati". A 200 that reads
     * as a failure is worse than an error: it invites the duplicate.
     *
     * Resolving the row also makes the failure honest when the category does
     * not exist. That used to surface as a foreign-key violation from the
     * database; now it is a plain 404 naming the id that was not found.
     */
    private void resolveCategory(Product product) {
        Category stub = product.getCategory();
        if (stub == null || stub.getId() == null) {
            return;
        }
        product.setCategory(categoryRepository.findById(stub.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Category not found with id " + stub.getId())));
    }

    // Save Product - evicts the cached listing so a new/changed product shows
    // up immediately instead of customers seeing a stale catalog.
    @CacheEvict(value = {"products", "brands", "newArrivals", "categoryProducts", "productDetail", "productSearch", "productFeed", "bestsellerTiles", "trending", "frequentlyBought"}, allEntries = true)
    @Transactional
    public ProductResponse saveProduct(Product product) {
        // NEVER AN UPDATE. save() on an entity carrying an id rewrites that
        // row, so an id arriving in the request body turns a create into an
        // overwrite of whichever product it named - renaming it, rebranding
        // it, or deactivating it - and answers 200 as though a create had
        // succeeded. The controller binds the raw Product entity, so the id
        // is bindable whether or not any client sends one.
        //
        // Identical guard, and identical reasoning, to CustomerService: the
        // customer path was fixed for exactly this and products were missed.
        if (product.getId() != null) {
            throw new BadRequestException("A new product cannot be created with an id.");
        }

        resolveCategory(product);
        applyModel3dUrl(product, product.getModel3dUrl(), true);
        // forAdmin: only an ADMIN can reach POST /api/products, and the reply
        // has to show them the privacy settings they just set - a
        // customer-shaped response would silently drop them and make the
        // toggle look like it had not saved.
        Product savedProduct = productRepository.save(product);
        return ProductResponse.forAdmin(savedProduct, shopPricedCatalogue.termsFor(savedProduct));
    }

    /**
     * Puts something new on the shelf of the shop making the request.
     *
     * <p>WHAT WAS BROKEN, AND IT WAS NOT THE SCREEN. Creating a product wrote
     * one row - the central catalogue entry - and nothing else. The merchant's
     * Products list asks a different question: {@code
     * ProductRepository.findAllListedForCurrentShop} returns products that have
     * a variant this shop has a {@code shop_product_variants} row for. A
     * catalogue row with no variant can never satisfy that EXISTS, so the
     * product was invisible to the merchant the instant it was written, on
     * every refresh, forever - and because it was invisible they could not open
     * it to add the variant that would have made it visible. Create Product
     * answered 200 and the list answered "No products yet", and both were
     * telling the truth about different things.
     *
     * <p>THE FIX IS THE UNIT OF WORK, NOT A CACHE FLUSH. What a merchant means
     * by "I sell this" is four rows, and they are written here in one
     * transaction or not at all:
     *
     * <ol>
     *   <li>{@code products} - the central catalogue entry, shared by every
     *       shop that ever sells it;</li>
     *   <li>{@code product_variants} - its first sellable form, also central;</li>
     *   <li>{@code shop_product_variants} - THIS shop's listing and price, which
     *       is the row the merchant's list and the customer's storefront both
     *       read, and the one that carries the tenant boundary;</li>
     *   <li>{@code inventory} - this shop's opening stock for it.</li>
     * </ol>
     *
     * <p>THE CENTRAL ROWS STAY CENTRAL ON PURPOSE. Two shops selling the
     * Motorola Edge 50 Pro point at one catalogue row and keep their own
     * listing, price and stock. Making the product itself shop-owned would fork
     * the catalogue per merchant and break the marketplace's whole premise -
     * and it is not needed for isolation, because the listing is what
     * ownership is read from.
     *
     * <p>WHY THE FIRST VARIANT IS REQUIRED IN A SHOP'S SCOPE. Without it this
     * method can only produce the orphan described above. Refusing is the
     * honest answer, and the message says what to type. A platform-scope
     * request (Super Admin seeding the central catalogue with no shop in
     * scope) has no shelf to land on, so there it stays optional.
     */
    @CacheEvict(value = {"products", "brands", "newArrivals", "categoryProducts", "productDetail", "productSearch", "productFeed", "bestsellerTiles", "trending", "frequentlyBought"}, allEntries = true)
    @Transactional
    public ProductResponse createProduct(com.gpstore.dto.request.ProductCreateRequest request) {
        com.gpstore.platform.TenantScope scope = com.gpstore.platform.TenantContext.current();
        boolean insideAShop = scope != null && scope.isSingleShop();
        // Required exactly when its absence would hide the product, and not a
        // moment before: the one-shop deployment's catalogue IS its shelf, so
        // demanding a price there would break a flow that works today for no
        // gain. See sellsAsOneOfManyShops.
        boolean shelfDecidesVisibility = sellsAsOneOfManyShops(scope);

        com.gpstore.dto.request.ProductCreateRequest.FirstVariant first = request.getFirstVariant();
        if (shelfDecidesVisibility && first == null) {
            log.warn("Refused a product create with no variant for shop {} - name={}",
                    scope == null ? null : scope.shopId(), request.getName());
            throw new BadRequestException(
                    "Add the first variant - its price and stock - so this product goes on "
                            + "your shelf. A product with no variant cannot be sold, and your "
                            + "Products list would not show it.");
        }

        Long categoryId = request.resolveCategoryId();
        if (categoryId == null) {
            throw new BadRequestException("Choose a category for this product.");
        }
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Category not found with id " + categoryId));

        // ALREADY ON THIS SHELF? A merchant double-tapping Create, or retrying
        // after a timeout that actually succeeded, must not end up with two
        // listings of one thing - which is how the live kirana catalogue ended
        // up with two "machar bati" rows (see resolveCategory). Matched on what
        // a person would call the same product rather than on a client-supplied
        // key, so a retry from a fresh app install is caught too.
        if (shelfDecidesVisibility) {
            productRepository.findAllListedForCurrentShop(
                            org.springframework.data.domain.PageRequest.of(0, ADMIN_UNPAGINATED_CAP))
                    .stream()
                    .filter(existing -> sameProduct(existing, request))
                    .findFirst()
                    .ifPresent(existing -> {
                        throw new com.gpstore.exception.ConflictException(
                                "You already sell \"" + existing.getName()
                                        + "\". Open it to add another variant instead.");
                    });
        }

        Product product = new Product();
        product.setName(request.getName().trim());
        product.setBrand(request.getBrand() == null || request.getBrand().isBlank()
                ? null : request.getBrand().trim());
        product.setDescription(request.getDescription());
        product.setCategory(category);
        product.setActive(request.getActive() == null ? Boolean.TRUE : request.getActive());
        applyModel3dUrl(product, request.getModel3dUrl(), true);
        Product saved = productRepository.save(product);

        if (first != null) {
            listFirstVariant(saved, first, insideAShop);
        }

        log.info("Shop {} added product {} (id={}) with {} first variant",
                scope == null ? null : scope.shopId(), saved.getName(), saved.getId(),
                first == null ? "no" : "a");
        return ProductResponse.forAdmin(saved, shopPricedCatalogue.termsFor(saved));
    }

    /**
     * The variant, the listing and the opening stock - the half of a create
     * that makes the product real to the shop that made it.
     *
     * <p>The listing is checked rather than assumed: {@link
     * com.gpstore.catalog.shop.ShopCatalog#list} returns null when there is no
     * shop in scope or no usable price, and letting that pass silently would
     * re-create the invisible product this whole method exists to prevent.
     */
    private void listFirstVariant(Product product,
                                  com.gpstore.dto.request.ProductCreateRequest.FirstVariant first,
                                  boolean insideAShop) {
        if (insideAShop && (first.getCommerceMode() == null
                || first.getCommerceMode().isBlank())) {
            throw new BadRequestException("Choose how customers obtain this item.");
        }
        com.gpstore.entity.ProductVariant variant = new com.gpstore.entity.ProductVariant();
        variant.setProduct(product);
        variant.setQuantity(first.getQuantity());
        // The label is what a merchant typed to tell two variants apart, and it
        // is stored in `unit` because that is the free-text half of the pair -
        // "12 GB + 256 GB" for a phone, "kg" for atta. Nothing here assumes a
        // pack size.
        String described = first.describe();
        variant.setUnit(first.getUnit() != null && !first.getUnit().isBlank()
                ? first.getUnit().trim()
                : described);
        variant.setSku(blankToNull(first.getSku()));
        variant.setBarcode(blankToNull(first.getBarcode()));
        variant.setImageUrl(blankToNull(first.getImageUrl()));
        variant.setSellingPrice(first.getSellingPrice());
        variant.setMrp(first.getMrp());
        variant.setCostPrice(first.getCostPrice());
        variant.setAvailable(first.getAvailable() == null ? Boolean.TRUE : first.getAvailable());
        variant.setActive(Boolean.TRUE);

        com.gpstore.entity.ProductVariant savedVariant = productVariants.save(variant);

        com.gpstore.catalog.shop.ShopProductVariant listing = shopCatalog.list(savedVariant);
        if (insideAShop && listing == null) {
            // Should be unreachable: the request validation already requires a
            // positive selling price and we know a shop is in scope. Loud
            // rather than silent, because the silent version is the bug.
            log.error("Variant {} was created but ShopCatalog declined to list it - "
                    + "the transaction is being rolled back", savedVariant.getId());
            throw new IllegalStateException(
                    "Variant " + savedVariant.getId() + " was created but not listed for shop "
                            + com.gpstore.platform.TenantContext.require().shopId());
        }

        // HOW THIS SHOP SELLS IT, applied to the listing that was just created.
        //
        // SET HERE RATHER THAN BY A FOLLOW-UP EDIT, and that is not a
        // convenience: a Visit-to-Buy item created as an online listing and
        // corrected a moment later is buyable in that gap. On a marketplace
        // with live customers that gap is an order the shop cannot fulfil.
        if (listing != null) {
            applyFirstVariantSellingMode(listing, first);
            shopCatalog.save(listing);
        }

        com.gpstore.entity.Inventory stock = new com.gpstore.entity.Inventory();
        stock.setProductVariant(savedVariant);
        stock.setStock(first.getStock() == null ? 0 : first.getStock());
        stock.setReservedStock(0);
        inventory.save(stock);
    }

    /**
     * Reads the selling mode off the create request onto the new listing.
     *
     * <p>For a shop-owned create the mode is required before any row is
     * committed. Existing rows were backfilled by V74; silently defaulting a
     * new merchant request is how an in-person item becomes online inventory.
     *
     * <p>AN UNKNOWN VALUE IS REFUSED, not silently defaulted. Defaulting would
     * quietly make a Visit-to-Buy item buyable, which is the one direction
     * this must never fail in; an error tells the merchant their app is out
     * of date while the item stays uncreated.
     */
    private static void applyFirstVariantSellingMode(
            com.gpstore.catalog.shop.ShopProductVariant listing,
            com.gpstore.dto.request.ProductCreateRequest.FirstVariant first) {
        listing.setCommerceMode(parseOrRefuse(
                com.gpstore.catalog.shop.CommerceMode.class, first.getCommerceMode(),
                com.gpstore.catalog.shop.CommerceMode.ONLINE_PURCHASE, "selling mode"));
        listing.setPriceMode(parseOrRefuse(
                com.gpstore.catalog.shop.ListingPriceMode.class, first.getPriceMode(),
                com.gpstore.catalog.shop.ListingPriceMode.EXACT_PRICE, "price mode"));
        listing.setPriceMax(first.getPriceMax());
        listing.setOfflineAvailability(parseOrRefuse(
                com.gpstore.catalog.shop.OfflineAvailability.class,
                first.getOfflineAvailability(), null, "availability"));
        listing.setServiceDurationMinutes(first.getServiceDurationMinutes());

        boolean online = listing.getCommerceMode() == null
                || listing.getCommerceMode().isBuyableOnline();

        // AN ONLINE PRICE IS A PROMISE - a cart totals it and a receipt prints
        // it - so the same rule the edit path enforces applies at creation.
        if (online && listing.getPriceMode() != com.gpstore.catalog.shop.ListingPriceMode.EXACT_PRICE) {
            throw new BadRequestException(
                    "An item sold online needs one exact price, because that is what the "
                            + "customer is charged.");
        }
        if (listing.getPriceMode() == com.gpstore.catalog.shop.ListingPriceMode.PRICE_RANGE
                && (listing.getPriceMax() == null
                    || listing.getPriceMax().compareTo(java.math.BigDecimal.ZERO) <= 0)) {
            throw new BadRequestException("A price range needs a top price.");
        }
        if (!online && listing.getOfflineAvailability() == null) {
            listing.setOfflineAvailability(
                    com.gpstore.catalog.shop.OfflineAvailability.AVAILABLE);
        }
        if (online) {
            listing.setServiceDurationMinutes(null);
        }
    }

    private static <E extends Enum<E>> E parseOrRefuse(Class<E> type, String raw,
                                                       E whenAbsent, String what) {
        if (raw == null || raw.isBlank()) {
            return whenAbsent;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BadRequestException("Unknown " + what + ": " + raw);
        }
    }

    /**
     * Whether these are the same thing to a shopkeeper: same name and same
     * brand, ignoring case and surrounding spaces.
     */
    private static boolean sameProduct(Product existing,
                                       com.gpstore.dto.request.ProductCreateRequest request) {
        return equalsLoosely(existing.getName(), request.getName())
                && equalsLoosely(existing.getBrand(), request.getBrand());
    }

    private static boolean equalsLoosely(String a, String b) {
        String left = a == null ? "" : a.trim();
        String right = b == null ? "" : b.trim();
        return left.equalsIgnoreCase(right);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // A public, unauthenticated GET endpoint backs each of the three methods
    // below (see ProductController) - none of them may ever run an unbounded
    // findAll()/findByX() again. Real clients use the paginated/ranked
    // /api/products/search/instant endpoint; these three exist only for
    // backward compatibility with old callers that expect a bare JSON array,
    // capped at a safe size instead of the whole catalog.
    private static final int ADMIN_UNPAGINATED_CAP = 100;

    // Customer-facing list. Paged by the controller (default 20, cap 50).
    // Only products that can actually be sold: active + a priced available
    // variant. A name with empty variants is not a product in the shop window.
    @Transactional(readOnly = true)
    @Cacheable(value = "products", sync = true)
    public List<ProductResponse> getAllProducts(org.springframework.data.domain.Pageable pageable) {
        return batchFetchWithVariants(productRepository.findSellable(requireListing(), pageable)).getContent();
    }

    /**
     * Admin management view - includes inactive/deactivated products too.
     *
     * <p>A platform administrator sees the central catalogue. A merchant in a
     * marketplace sees only products and variants represented by listing rows
     * on the authenticated shop's shelf. The scope is server-derived; there
     * is no shop id argument to manipulate. A genuine one-shop deployment
     * retains the legacy central-catalogue editor unchanged.
     */
    @Transactional(readOnly = true)
    public List<ProductResponse> getAllForAdmin() {
        org.springframework.data.domain.PageRequest page =
                org.springframework.data.domain.PageRequest.of(0, ADMIN_UNPAGINATED_CAP);
        com.gpstore.platform.TenantScope scope =
                com.gpstore.platform.TenantContext.require();
        boolean marketplaceMerchant = sellsAsOneOfManyShops(scope);

        if (scope.isPlatform()) {
            // There is no single shop price in a platform scope. Passing all
            // listing rows to a map keyed only by variant id would choose an
            // arbitrary merchant's price when several shops sell the same
            // variant, so the platform view deliberately shows the central
            // catalogue defaults here. Per-shop prices live in the control
            // tower's product resource, keyed by shop.
            return productRepository.findAllByOrderByCreatedAtDesc(page)
                    .map(ProductResponse::forAdmin)
                    .toList();
        }

        if (!marketplaceMerchant) {
            return productRepository.findAllByOrderByCreatedAtDesc(page)
                    .map(p -> ProductResponse.forAdmin(
                            p, shopPricedCatalogue.termsFor(p)))
                    .toList();
        }

        List<Long> orderedIds = productRepository.findAllListedForCurrentShop(page)
                .stream().map(Product::getId).toList();
        if (orderedIds.isEmpty()) {
            return List.of();
        }
        Map<Long, Product> byId = new HashMap<>();
        for (Product product : productRepository.findByIdIn(orderedIds)) {
            byId.put(product.getId(), product);
        }
        Map<Long, com.gpstore.catalog.shop.ShopProductVariant> shopTerms =
                shopPricedCatalogue.termsFor(byId.values());
        return orderedIds.stream()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .map(product -> ProductResponse.forShopAdmin(product, shopTerms))
                .toList();
    }

    // Kept for backward compatibility with any existing caller of the old,
    // unranked, unpaginated search.
    @Transactional(readOnly = true)
    public List<ProductResponse> search(String keyword) {
        return productRepository
                .findByNameContainingIgnoreCase(keyword, org.springframework.data.domain.PageRequest.of(0, ADMIN_UNPAGINATED_CAP))
                .stream()
                .map(p -> ProductResponse.fromCard(p, shopPricedCatalogue.termsFor(p)))
                .toList();
    }

    /**
     * Instant, typo-tolerant, relevance-ranked search - see
     * ProductRepository.searchInstant(). That native query returns bare
     * Product entities with nothing eager-fetched (same situation
     * batchFetchWithVariants exists for), so this used to lazy-load each
     * result's category and variants one product at a time while mapping to
     * ProductResponse - confirmed via isolated timing as the actual cause of
     * a multi-second response that was identical regardless of keyword
     * (same page size, same ~40 extra sequential round trips either way).
     * Batching the re-fetch the same way browseByCategory/getNewArrivals
     * already do fixes it in one extra round trip instead.
     */
    // Cached (keyed on keyword+page, Spring's default composite key) because
    // grocery search terms repeat heavily across different customers (many
    // different people search "rice", "milk", "oil" on any given day) - a
    // cache hit skips the database round trips entirely, not just the N+1
    // this method already avoids.
    @Transactional(readOnly = true)
    @Cacheable(value = "productSearch", sync = true)
    public Page<ProductResponse> searchInstant(String keyword, Pageable pageable) {
        if (keyword == null || keyword.isBlank()) {
            throw new BadRequestException("Search keyword is required");
        }
        String trimmed = keyword.trim();
        if (trimmed.length() > 80) {
            trimmed = trimmed.substring(0, 80);
        }
        int page = Math.max(pageable.getPageNumber(), 0);
        int size = Math.min(Math.max(pageable.getPageSize(), 1), 50);
        ProductBrowseRepository.SearchPage found =
                productBrowseRepository.searchInstant(trimmed, page, size);
        return new PageImpl<>(
                batchToResponseList(found.productIds()),
                org.springframework.data.domain.PageRequest.of(page, size),
                found.totalElements());
    }

    /**
     * The endless home feed: every active product, page by page.
     *
     * This is what lets the home screen keep going past New Arrivals instead
     * of ending after three carousels. It is a genuine server-side page - the
     * client asks for page N and gets 20 products plus whether more exist -
     * so a catalogue of thousands is never loaded into memory at once, on
     * either side.
     *
     * SORTED BY ID ASCENDING, and that matters more than it looks. Infinite
     * scroll re-queries with an offset, so the sort has to be stable: with
     * createdAt DESC, a product added mid-scroll shifts every later page and
     * the customer sees a duplicate or skips an item. New ids land at the
     * end, leaving already-fetched pages meaning exactly what they meant.
     *
     * Cached like the other browse paths. The cache key includes the
     * Pageable, so each page is cached independently rather than the whole
     * catalogue under one key.
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "productFeed", sync = true)
    public Page<ProductResponse> browseAll(Pageable pageable) {
        return batchFetchWithVariants(productRepository.findSellable(requireListing(), pageable));
    }

    /**
     * The whole Bestsellers collage in one call.
     *
     * REPLACES SIX REQUESTS. The app was calling browseByCategory once per
     * category tile on every cold home open - six HTTP round trips, six auth
     * filter chains, six connection acquisitions - to render twenty-four
     * thumbnails. This is one request backed by one SQL statement, and it
     * stays one of each however many tiles the collage grows to.
     *
     * Both limits are bounded by the caller and clamped here, so a crafted
     * query string cannot turn the collage endpoint into a full catalogue
     * dump. perCategory defaults to what the UI actually draws - four - not
     * to a page size of twenty.
     *
     * Cached. The collage changes only when the catalogue does, and it is
     * requested by every customer who opens the app.
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "bestsellerTiles", sync = true)
    public List<BestsellerTileResponse> getBestsellerTiles(int categoryLimit, int perCategory) {
        int categories = clamp(categoryLimit, 1, MAX_BESTSELLER_CATEGORIES);
        int products = clamp(perCategory, 1, MAX_BESTSELLER_PRODUCTS_PER_CATEGORY);

        // LinkedHashMap: the query already returns rows grouped and ordered
        // by category, and the collage should render them in that order
        // rather than whatever a HashMap happens to iterate in.
        Map<Long, BestsellerTileResponse> tiles = new LinkedHashMap<>();
        for (ProductBrowseRepository.BestsellerRow row :
                productBrowseRepository.findBestsellerTiles(null, categories, products)) {
            BestsellerTileResponse tile = tiles.computeIfAbsent(
                    row.categoryId(),
                    id -> new BestsellerTileResponse(
                            id, row.categoryName(), new ArrayList<>(), new ArrayList<>(),
                            // Identical on every row of this category - taking
                            // it from the first is not a shortcut, it is the
                            // only row that creates the tile.
                            row.categoryTotal()));
            tile.getProductIds().add(row.productId());
            // Added even when null - see BestsellerTileResponse.imageUrls for
            // why the slot is kept rather than skipped.
            tile.getImageUrls().add(com.gpstore.upload.CatalogImageDelivery.forClient(row.imageUrl()));
        }
        return new ArrayList<>(tiles.values());
    }

    /** The collage is six tiles of four; these are the ceilings, not the defaults. */
    private static final int MAX_BESTSELLER_CATEGORIES = 12;
    private static final int MAX_BESTSELLER_PRODUCTS_PER_CATEGORY = 8;

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Category browsing - the other half of product discovery alongside search. */
    @Transactional(readOnly = true)
    @Cacheable(value = "categoryProducts", sync = true)
    public Page<ProductResponse> browseByCategory(Long categoryId, Pageable pageable) {
        return batchFetchWithVariants(productRepository.findSellableByCategoryId(categoryId, requireListing(), pageable));
    }

    /**
     * Sort/filter/search version of category browsing - same options and
     * same reasoning as "Shop by Brand" (see browseByBrand's doc comment).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> browseByCategoryFiltered(
            Long categoryId, String sort, boolean inStockOnly, String keyword, int page, int size) {

        ProductBrowseRepository.BrowseResult result =
                productBrowseRepository.browse(null, categoryId, sort, inStockOnly, keyword, page, size);
        return toBrowseResponse(result, page, size);
    }

    /** Real "New Arrivals" - sorted by actual creation time, not fabricated. */
    @Transactional(readOnly = true)
    @Cacheable(value = "newArrivals", sync = true)
    public Page<ProductResponse> getNewArrivals(Pageable pageable) {
        return batchFetchWithVariants(productRepository.findSellable(
                requireListing(),
                org.springframework.data.domain.PageRequest.of(
                        pageable.getPageNumber(),
                        pageable.getPageSize(),
                        org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.DESC, "createdAt")
                                .and(org.springframework.data.domain.Sort.by(
                                        org.springframework.data.domain.Sort.Direction.DESC, "id")))));
    }

    /**
     * Turns a Page<Product> (eager-fetched category only, per
     * ProductRepository's @EntityGraph comments - NOT variants, to avoid
     * Hibernate's collection-fetch+pagination trap) into a Page<ProductResponse>
     * with variants populated too, without the N+1 that ProductResponse.from()
     * would otherwise trigger by lazy-loading each product's variants one at
     * a time. Same batching trick as RecommendationService.fetchInRankedOrder:
     * one extra query for every product ID on this page at once (via
     * findByIdIn's @EntityGraph({"category","variants"})), instead of one
     * lazy-load query per product - for a 20-item page, 20 sequential round
     * trips instead of 1, which was slow enough to blow past the app's
     * request timeout on every attempt (so this endpoint's own @Cacheable
     * never even got a chance to populate the cache).
     *
     * findByIdIn doesn't preserve input order, so results are re-sorted back
     * into the original page's order (whatever sort the caller asked for)
     * before re-wrapping as a Page, preserving the original totalElements/
     * totalPages metadata that came from the real paginated query.
     */
    private Page<ProductResponse> batchFetchWithVariants(Page<Product> page) {
        List<Long> orderedIds = page.getContent().stream().map(Product::getId).toList();
        return new PageImpl<>(batchToResponseList(orderedIds), page.getPageable(), page.getTotalElements());
    }

    /**
     * Same batching trick, keyed by ID so it works for both a Page<Product>'s
     * content (see batchFetchWithVariants) and ProductBrowseRepository's
     * plain sorted List<Product> - its native query returns bare entities
     * with no relations eager-fetched at all, so every product's category
     * AND variants would otherwise lazy-load one at a time.
     */
    private List<ProductResponse> batchToResponseList(List<Long> orderedIds) {
        if (orderedIds.isEmpty()) {
            return List.of();
        }

        Map<Long, Product> byId = new HashMap<>();
        for (Product product : productRepository.findByIdIn(orderedIds)) {
            byId.put(product.getId(), product);
        }

        // One lookup for this shop's price on every variant on the page, in the
        // same spirit as the batch above: a twenty-product grid is one query,
        // not one per size. The prices are this shop's, and the cache entry
        // they end up in is keyed by shop (see CacheConfig.keyGenerator).
        Map<Long, com.gpstore.catalog.shop.ShopProductVariant> shopTerms =
                shopPricedCatalogue.termsFor(byId.values());

        // AND ONE FOR STOCK, batched the same way and for the same reason.
        // Without it every card on the grid says "add to basket" for a size
        // this shop has run out of, and the customer is refused at the moment
        // they tap it - the refusal has always been there (§7 STATE 2), the
        // card simply had no way to know.
        Map<Long, Integer> held = shopStock.heldFor(byId.values());

        List<ProductResponse> content = new ArrayList<>(orderedIds.size());
        for (Long id : orderedIds) {
            Product product = byId.get(id);
            if (product != null) {
                content.add(ProductResponse.fromCard(product, shopTerms, held));
            }
        }
        return content;
    }

    /**
     * Only brands with at least one active product - guaranteed by the
     * underlying GROUP BY query - and, under a marketplace, only brands this
     * shop actually lists. The cache entry is keyed by shop
     * (CacheConfig.keyGenerator), so two storefronts do not share one answer.
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "brands", sync = true)
    public List<com.gpstore.dto.response.BrandSummary> getBrandsWithCounts() {
        return productRepository.findBrandsWithProductCounts(requireListing()).stream()
                .map(row -> new com.gpstore.dto.response.BrandSummary((String) row[0], (Long) row[1]))
                .toList();
    }

    /**
     * "Shop by Brand" product browsing - sorted, filtered, searched, and
     * paginated entirely in SQL (see ProductBrowseRepository's doc comment)
     * instead of loading the whole brand's product set into Java to sort -
     * including, for Best Selling/Highest Rated, no longer pulling the
     * entire order_items/reviews tables into a HashMap on every request.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> browseByBrand(
            String brand, String sort, boolean inStockOnly, String keyword, int page, int size) {

        ProductBrowseRepository.BrowseResult result =
                productBrowseRepository.browse(brand, null, sort, inStockOnly, keyword, page, size);
        return toBrowseResponse(result, page, size);
    }

    /** Shared response shape for both browseByBrand and browseByCategoryFiltered. */
    private Map<String, Object> toBrowseResponse(ProductBrowseRepository.BrowseResult result, int page, int size) {
        List<ProductResponse> content = batchToResponseList(result.products().stream().map(Product::getId).toList());
        int totalPages = (int) Math.ceil(result.totalElements() / (double) size);

        Map<String, Object> response = new HashMap<>();
        response.put("content", content);
        response.put("totalElements", result.totalElements());
        response.put("totalPages", totalPages);
        response.put("number", page);
        response.put("size", size);
        return response;
    }

    // Product detail is the single most-tapped customer-facing endpoint (every
    // "view product" hits it) - plain findById() left category/variants lazy,
    // so ProductResponse.from() cost 2 extra round trips beyond the initial
    // fetch (3 total for what should be 1), on every single view, uncached.
    // findByIdIn already eager-fetches both via @EntityGraph, so reusing it
    // with a single-element list gets the same result in one query, and
    // caching this (product data barely changes) means most views don't hit
    // the database at all.
    @Transactional(readOnly = true)
    @Cacheable("productDetail")
    public ProductResponse getProductById(Long id) {
        Product entity = productRepository.findByIdIn(List.of(id)).stream()
                .findFirst()
                .orElse(null);

        if (entity == null) {
            return null;
        }

        Map<Long, com.gpstore.catalog.shop.ShopProductVariant> terms =
                shopPricedCatalogue.termsFor(entity);

        // NOT ON THIS SHOP'S SHELF IS NOT FOUND, under a marketplace.
        //
        // Detail is reached by id, so it is the one browse surface a
        // customer can land on without having browsed: a deep link, a
        // shared card, a stale home screen from before they switched shops,
        // or simply an id typed into the URL. Every list around it is
        // narrowed to the shelf; if this one is not, the narrowing is
        // decoration - anybody can read any shop's catalogue one id at a
        // time. termsFor is shop-filtered (ShopCatalog), so an empty answer
        // means this shop does not list the product.
        //
        // Returning null rather than throwing gives it exactly the answer an
        // id that does not exist already gets, which is the right answer:
        // as far as this storefront is concerned, it does not. The cache is
        // keyed by shop (CacheConfig.keyGenerator), so this shop's "no" is
        // never served to a customer standing in another one.
        if (requireListing() && terms.values().stream().noneMatch(
                com.gpstore.catalog.shop.ShopProductVariant::isOrderable)) {
            return null;
        }

        ProductResponse product = ProductResponse.from(entity, terms, shopStock.heldFor(entity));

        // The 3D model, like the gallery below, is attached ONLY here.
        // ProductResponse.from deliberately leaves it null so that no list
        // response ever carries it - the field exists for one screen and
        // should cost nothing on every other one.
        if (entity.getModel3dUrl() != null && !entity.getModel3dUrl().isBlank()) {
            product = product.withModel3dUrl(entity.getModel3dUrl());
        }

        // The gallery is attached HERE and nowhere else, on purpose.
        //
        // Detail is the only screen that shows more than one image, so this
        // is the only place worth the extra query. Attaching galleries to
        // list responses would mean fetching up to five URLs for every card
        // in a 20-product grid to render one thumbnail - bandwidth and
        // serialization the user never sees, on every browse request, which
        // is exactly the traffic that already saturates this instance.
        //
        // Listings continue to use ProductVariant.imageUrl, unchanged.
        //
        // One extra query per detail view, and it is cached with the rest of
        // the response under "productDetail".
        List<String> gallery = productImageRepository.findByProductIdOrderBySortOrderAsc(id).stream()
                .map(com.gpstore.entity.ProductImage::getImageUrl)
                .filter(url -> url != null && !url.isBlank())
                .map(com.gpstore.upload.CatalogImageDelivery::forClient)
                .toList();

        // No gallery rows means an existing product that predates this
        // feature: return it exactly as before and let the client fall back
        // to the variant thumbnail, rather than handing back an empty
        // gallery the UI might render as a broken strip.
        product = gallery.isEmpty() ? product : product.withImages(gallery);

        // PER-VARIANT PHOTOS, in ONE query for every variant of this product.
        //
        // The front, back and side of the 1 kg packet are different pictures
        // from the front, back and side of the 500 g packet, which is why
        // these hang off the variant rather than the product. Asking per
        // variant would be an N+1 on precisely the screen the feature exists
        // for, so the whole product's worth is fetched at once and grouped
        // here.
        //
        // One extra query per detail view, on a response that is cached with
        // the rest of it under "productDetail". Listings are untouched and
        // still carry one thumbnail per card.
        List<Long> variantIds = product.getVariants() == null ? List.of()
                : product.getVariants().stream()
                        .map(com.gpstore.dto.response.VariantResponse::getId)
                        .filter(java.util.Objects::nonNull)
                        .toList();

        if (!variantIds.isEmpty()) {
            java.util.Map<Long, List<String>> byVariant = new java.util.LinkedHashMap<>();
            for (com.gpstore.entity.ProductImage image
                    : productImageRepository.findByProductVariantIdIn(variantIds)) {
                if (image.getImageUrl() == null || image.getImageUrl().isBlank()) {
                    continue;
                }
                byVariant.computeIfAbsent(image.getProductVariant().getId(), k -> new ArrayList<>())
                        .add(com.gpstore.upload.CatalogImageDelivery.forClient(image.getImageUrl()));
            }
            product = product.withVariantImages(byVariant);
        }

        return product;
    }

    public Product getByIdOrThrow(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new com.gpstore.exception.ResourceNotFoundException("Product not found"));
    }

    /** Didn't exist before - a product could be created but never edited afterward. */
    @CacheEvict(value = {"products", "brands", "newArrivals", "categoryProducts", "productDetail", "productSearch", "productFeed", "bestsellerTiles", "trending", "frequentlyBought"}, allEntries = true)
    @Transactional
    public ProductResponse update(Long id, Product updated) {
        Product existing = getByIdOrThrow(id);

        resolveCategory(updated);

        existing.setName(updated.getName());
        existing.setBrand(updated.getBrand());
        existing.setCategory(updated.getCategory());
        existing.setActive(updated.getActive());

        // Privacy is admin-configurable, so it has to be copied here or the
        // toggle would silently do nothing on edit. Only an ADMIN can reach
        // this endpoint (SecurityConfig gates PUT /api/products/**), which is
        // what stops a customer setting it - the check is the existing
        // authorisation, not a new one invented for this feature.
        existing.setIsPrivateProduct(updated.getIsPrivateProduct());
        existing.setCustomerDisplayName(updated.getCustomerDisplayName());
        // Null means omitted (Flutter updateProduct does not send model3dUrl).
        // Empty string clears it. A javascript: or http:// URL is refused.
        applyModel3dUrl(existing, updated.getModel3dUrl(), false);

        Product savedExisting = productRepository.save(existing);
        return ProductResponse.forAdmin(savedExisting, shopPricedCatalogue.termsFor(savedExisting));
    }

    private static void applyModel3dUrl(Product product, String url, boolean always) {
        if (!always && url == null) {
            return;
        }
        com.gpstore.catalog.CatalogUrlValidator.requireAllowedModel3dUrl(url);
        product.setModel3dUrl(com.gpstore.catalog.CatalogUrlValidator.trimToNull(url));
    }

    /**
     * Soft-delete only - same reasoning as Category: a hard delete would
     * orphan every variant/order-item/cart-item still referencing this
     * product, or fail on the FK constraint. Deactivating just stops it
     * showing up to customers.
     */
    @CacheEvict(value = {"products", "brands", "newArrivals", "categoryProducts", "productDetail", "productSearch", "productFeed", "bestsellerTiles", "trending", "frequentlyBought"}, allEntries = true)
    public void deactivate(Long id) {
        Product product = getByIdOrThrow(id);
        product.setActive(false);
        productRepository.save(product);
    }
}
