package com.gpstore.dto.response;

import com.gpstore.entity.ProductVariant;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * Explicit response shape instead of returning the raw ProductVariant entity.
 * Deliberately excludes barcode, sku, gstRateOverride, and costPrice - none of
 * these are read by the Flutter app, and costPrice is wholesale-margin data
 * that should never reach a customer-facing response regardless of the
 * entity's own @JsonProperty(WRITE_ONLY) protection.
 *
 * Serializable - see ProductResponse's doc comment (this is nested inside it).
 */
public class VariantResponse implements Serializable {

    /**
     * Pinned so this DTO can gain fields without invalidating entries
     * already sitting in Redis.
     *
     * Without an explicit value the JVM derives one from the class
     * structure, so adding a single field changes it and every cached entry
     * written by the previous build fails to deserialise with
     * InvalidClassException on the next read. Spring's default cache error
     * handler rethrows that, which turns a routine DTO change into a 500 on
     * every browse request until the TTL drains - see CacheConfig, which
     * now also makes that survivable.
     *
     * Java's rules make ADDING a field compatible once this is pinned;
     * removing or retyping one is not, and still needs a cache flush.
     */
    private static final long serialVersionUID = 1L;

    private final Long id;
    private final Double quantity;
    private final String unit;
    private final String imageUrl;
    private final Boolean available;
    private final BigDecimal mrp;
    private final BigDecimal sellingPrice;
    private final Integer displayOrder;

    /**
     * Whether THIS SHOP actually holds any right now.
     *
     * <p>NULL MEANS NOT REPORTED, and that is a third state on purpose. This
     * field is filled in on the customer-facing browse paths, where the app
     * needs it to draw an "Out of stock" card; an admin catalogue screen or a
     * platform-wide report has no shop whose stock it could mean. A caller
     * that cannot report stock must not be able to accidentally report zero -
     * a false "out of stock" hides a product the shop is selling, which is a
     * worse failure than showing an enabled button.
     *
     * <p>DISTINCT FROM {@link #available}, which is "this shop lists it". A
     * shop can list an item it has run out of - that is the ordinary state of
     * a kirana at the end of a Sunday - and the two questions have different
     * answers and different consequences.
     */
    private final Boolean inStock;

    /**
     * This variant's photos, in order. First is the primary.
     *
     * EMPTY ON EVERY LIST RESPONSE, and that is deliberate rather than an
     * oversight. Attached only by ProductService.getProductById, because the
     * detail screen is the only one that shows more than a thumbnail -
     * carrying five URLs per variant through a twenty-product grid would be
     * bandwidth and serialisation nobody ever sees, on exactly the traffic
     * that saturates this instance.
     *
     * Listings keep reading {@link #imageUrl}, which the write path keeps
     * equal to the first photo - so the primary image appears everywhere the
     * old single field already did, with no client change.
     */
    private final java.util.List<String> images;

    /** Same variant, with its gallery attached. Used on the detail screen only. */
    public VariantResponse withImages(java.util.List<String> gallery) {
        return new VariantResponse(id, quantity, unit, imageUrl, available, mrp, sellingPrice,
                displayOrder, gallery, inStock);
    }

    public VariantResponse(Long id, Double quantity, String unit, String imageUrl,
                            Boolean available, BigDecimal mrp, BigDecimal sellingPrice,
                            Integer displayOrder) {
        this(id, quantity, unit, imageUrl, available, mrp, sellingPrice, displayOrder,
                java.util.List.of(), null);
    }

    public VariantResponse(Long id, Double quantity, String unit, String imageUrl,
                            Boolean available, BigDecimal mrp, BigDecimal sellingPrice,
                            Integer displayOrder, java.util.List<String> images) {
        this(id, quantity, unit, imageUrl, available, mrp, sellingPrice, displayOrder,
                images, null);
    }

    public VariantResponse(Long id, Double quantity, String unit, String imageUrl,
                            Boolean available, BigDecimal mrp, BigDecimal sellingPrice,
                            Integer displayOrder, java.util.List<String> images,
                            Boolean inStock) {
        this.id = id;
        this.quantity = quantity;
        this.unit = unit;
        this.imageUrl = imageUrl;
        this.available = available;
        this.mrp = mrp;
        this.sellingPrice = sellingPrice;
        this.displayOrder = displayOrder;
        this.images = images == null ? java.util.List.of() : java.util.List.copyOf(images);
        this.inStock = inStock;
    }

    public static VariantResponse from(ProductVariant variant) {
        return from(variant, null, null);
    }

    /** Without stock: the catalogue and admin paths, which have no shop's shelf to count. */
    public static VariantResponse from(ProductVariant variant,
                                       com.gpstore.catalog.shop.ShopProductVariant listing) {
        return from(variant, listing, null);
    }

    /**
     * The catalogue's description of the item, at THIS shop's price.
     *
     * WHAT COMES FROM WHERE. Everything a customer identifies the item by -
     * pack size, unit, photo - is the catalogue's, shared by every shop that
     * sells it. Everything commercial - the price, whether it is listed, the
     * printed MRP if this shop's pack differs - comes from the shop's own
     * listing. A null listing means the caller had no shop context (an admin
     * catalogue screen, a platform-wide report), and the catalogue's own
     * defaults stand.
     */
    /**
     * @param heldStock how many of this variant the shop in scope holds, or
     *                  null when the caller has no shop to count for
     */
    public static VariantResponse from(ProductVariant variant,
                                       com.gpstore.catalog.shop.ShopProductVariant listing,
                                       Integer heldStock) {
        if (variant == null) {
            return null;
        }
        // §10 (PART 2): NO PRICE ON AN EMPTY SHELF.
        //
        // "Keep product visible. Show Out of stock. Disable Add to Cart. HIDE
        // PRICE." The first three were already true - the card reads
        // available && inStock and draws a grey "Sold out". The fourth was
        // not: the price went on being sent, so every client was one styling
        // decision away from showing "₹45" beside a button that refuses.
        //
        // WITHHELD HERE, IN THE RESPONSE, rather than by a screen choosing
        // not to draw it. There are three clients and a public API; a rule
        // enforced in one Dart widget is a rule the other two do not have.
        //
        // ONLY WHEN THE STOCK IS KNOWN TO BE ZERO. heldStock is null wherever
        // there is no shop to count for - an admin catalogue screen, a
        // platform-wide report - and null must keep its price, or the
        // merchant's own product list goes blank.
        boolean shelfIsEmpty = heldStock != null && heldStock <= 0;

        return new VariantResponse(
                variant.getId(),
                variant.getQuantity(),
                variant.getUnit(),
                variant.getImageUrl() == null
                        ? null
                        : com.gpstore.upload.CatalogImageDelivery.forClient(variant.getImageUrl()),
                // Boolean.valueOf, not the bare boolean: a ternary with a
                // primitive on one arm unboxes the other, so writing
                // isOrderable() here would NPE on any catalogue variant whose
                // available flag is null. Caught by RecommendationHygieneTest.
                listing != null ? Boolean.valueOf(listing.isOrderable()) : variant.getAvailable(),
                shelfIsEmpty ? null
                        : (listing != null && listing.getMrp() != null
                                ? listing.getMrp() : variant.getMrp()),
                shelfIsEmpty ? null
                        : (listing != null ? listing.getSellingPrice() : variant.getSellingPrice()),
                listing != null && listing.getDisplayOrder() != null
                        ? listing.getDisplayOrder() : variant.getDisplayOrder(),
                java.util.List.of(),
                heldStock == null ? null : Boolean.valueOf(heldStock > 0)
        );
    }

    public Long getId() { return id; }
    public Double getQuantity() { return quantity; }
    public String getUnit() { return unit; }
    public String getImageUrl() { return imageUrl; }
    public Boolean getAvailable() { return available; }
    public Boolean getInStock() { return inStock; }
    public BigDecimal getMrp() { return mrp; }
    public BigDecimal getSellingPrice() { return sellingPrice; }
    public Integer getDisplayOrder() { return displayOrder; }
    public java.util.List<String> getImages() { return images; }
}
