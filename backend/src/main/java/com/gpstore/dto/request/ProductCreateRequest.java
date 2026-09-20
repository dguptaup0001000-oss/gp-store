package com.gpstore.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * What a merchant fills in to put something new on their shelf.
 *
 * <p>WHY THIS EXISTS RATHER THAN BINDING THE Product ENTITY. A product row on
 * its own is not something anybody sells. The merchant's Products screen asks
 * the shelf - "what does THIS shop list?" - which is answered from
 * shop_product_variants, so a catalogue row with no variant and no listing is
 * invisible the moment it is written. That is exactly what happened on a real
 * device: Create Product answered 200, the screen popped back, and the list
 * still said "No products yet". A create that cannot be seen by the person who
 * made it is not a create.
 *
 * <p>So the unit of creation is the whole thing a merchant means by "I sell
 * this": the catalogue entry, its first variant, this shop's listing for that
 * variant, and the opening stock. {@code firstVariant} is what carries the
 * second half, and for a request made inside a shop's scope it is required -
 * see ProductService.createProduct.
 *
 * <p>DELIBERATELY NOT GROCERY-SHAPED. {@code label} and {@code unit} are free
 * text because a variant is whatever distinguishes one sellable thing from
 * another in THAT trade: "8 GB + 256 GB" for a phone, "Banarasi silk, red" for
 * a saree, "1 kg" for atta, "Half" for a plate of food. Nothing here says
 * pack size.
 */
public class ProductCreateRequest {

    @NotBlank(message = "Product name is required.")
    @Size(max = 255, message = "Product name is too long.")
    private String name;

    @Size(max = 255, message = "Brand name is too long.")
    private String brand;

    private String description;

    private Long categoryId;

    /**
     * The shape the shipped admin APK sends: {@code "category": {"id": 7}}.
     *
     * <p>KEPT BECAUSE PHONES IN THE FIELD SEND IT. Merchants are running a
     * build that posts the category as a nested stub, and an API that only
     * accepted the flat {@code categoryId} would turn every Add Product on
     * every installed copy into a 400 the moment this deployed. Either spelling
     * resolves to the same row - see {@link #resolveCategoryId()}.
     */
    private CategoryRef category;

    private Boolean active;

    private String model3dUrl;

    @Valid
    private FirstVariant firstVariant;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }

    public CategoryRef getCategory() { return category; }
    public void setCategory(CategoryRef category) { this.category = category; }

    /**
     * The category id, however the client spelled it.
     *
     * <p>Validated here rather than with {@code @NotNull} on either field,
     * because neither one alone is required - exactly one of them is.
     */
    public Long resolveCategoryId() {
        if (categoryId != null) {
            return categoryId;
        }
        if (category != null && category.getId() != null) {
            return category.getId();
        }
        return null;
    }

    /** The nested {@code {"id": N}} stub the shipped admin app sends. */
    public static class CategoryRef {
        private Long id;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
    }

    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }

    public String getModel3dUrl() { return model3dUrl; }
    public void setModel3dUrl(String model3dUrl) { this.model3dUrl = model3dUrl; }

    public FirstVariant getFirstVariant() { return firstVariant; }
    public void setFirstVariant(FirstVariant firstVariant) { this.firstVariant = firstVariant; }

    /**
     * The first sellable form of the product, and this shop's terms for it.
     *
     * <p>{@code sellingPrice} is required and must be above zero, because
     * ShopCatalog.list() silently declines to list a variant priced at null or
     * zero. A request that produced an unlisted variant would land the merchant
     * back exactly where this class exists to stop them being: a product they
     * created and cannot see.
     */
    public static class FirstVariant {

        /**
         * What the merchant calls this variant - "12 GB + 256 GB", "Red",
         * "1 kg", "Full plate". Optional: a shop that sells exactly one form of
         * something has nothing to write here.
         */
        @Size(max = 120, message = "Variant label is too long.")
        private String label;

        /** The numeric part, where the trade has one (1, 500, 12). */
        private Double quantity;

        /** The unit for that number, where the trade has one (kg, g, GB, ml). */
        @Size(max = 40, message = "Unit is too long.")
        private String unit;

        @NotNull(message = "Set a selling price.")
        @Positive(message = "Selling price must be more than zero.")
        private BigDecimal sellingPrice;

        @PositiveOrZero(message = "MRP cannot be negative.")
        private BigDecimal mrp;

        @PositiveOrZero(message = "Cost price cannot be negative.")
        private BigDecimal costPrice;

        @Size(max = 120, message = "SKU is too long.")
        private String sku;

        @Size(max = 120, message = "Barcode is too long.")
        private String barcode;

        private String imageUrl;

        /**
         * Opening stock. Null means "not counted yet" and becomes zero rather
         * than being guessed at - a shop that has not counted is not a shop
         * with one of everything.
         */
        @PositiveOrZero(message = "Stock cannot be negative.")
        private Integer stock;

        private Boolean available;


        /**

         * HOW this shop sells it: online, over the counter, or as a service.

         *

         * <p>NULL MEANS ONLINE, which is what every product created before

         * commerce modes existed is, so a caller that does not send these keeps

         * behaving exactly as it did. They are here so the Visit-to-Buy and

         * Services screens can create a listing that is already in the right mode

         * rather than creating an online one and immediately editing it - which

         * would leave a window where the item is buyable.

         */

        @Size(max = 24, message = "Unknown selling mode.")

        private String commerceMode;


        @Size(max = 24, message = "Unknown price mode.")

        private String priceMode;


        private BigDecimal priceMax;


        @Size(max = 24, message = "Unknown availability.")

        private String offlineAvailability;


        private Integer serviceDurationMinutes;


        public String getCommerceMode() { return commerceMode; }

        public void setCommerceMode(String commerceMode) { this.commerceMode = commerceMode; }


        public String getPriceMode() { return priceMode; }

        public void setPriceMode(String priceMode) { this.priceMode = priceMode; }


        public BigDecimal getPriceMax() { return priceMax; }

        public void setPriceMax(BigDecimal priceMax) { this.priceMax = priceMax; }


        public String getOfflineAvailability() { return offlineAvailability; }

        public void setOfflineAvailability(String offlineAvailability) {

            this.offlineAvailability = offlineAvailability;

        }


        public Integer getServiceDurationMinutes() { return serviceDurationMinutes; }

        public void setServiceDurationMinutes(Integer serviceDurationMinutes) {

            this.serviceDurationMinutes = serviceDurationMinutes;

        }

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }

        public Double getQuantity() { return quantity; }
        public void setQuantity(Double quantity) { this.quantity = quantity; }

        public String getUnit() { return unit; }
        public void setUnit(String unit) { this.unit = unit; }

        public BigDecimal getSellingPrice() { return sellingPrice; }
        public void setSellingPrice(BigDecimal sellingPrice) { this.sellingPrice = sellingPrice; }

        public BigDecimal getMrp() { return mrp; }
        public void setMrp(BigDecimal mrp) { this.mrp = mrp; }

        public BigDecimal getCostPrice() { return costPrice; }
        public void setCostPrice(BigDecimal costPrice) { this.costPrice = costPrice; }

        public String getSku() { return sku; }
        public void setSku(String sku) { this.sku = sku; }

        public String getBarcode() { return barcode; }
        public void setBarcode(String barcode) { this.barcode = barcode; }

        public String getImageUrl() { return imageUrl; }
        public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

        public Integer getStock() { return stock; }
        public void setStock(Integer stock) { this.stock = stock; }

        public Boolean getAvailable() { return available; }
        public void setAvailable(Boolean available) { this.available = available; }

        /**
         * The variant's display name, built from whatever the trade gave us.
         *
         * <p>A phone shop types "12 GB + 256 GB" into label and leaves the
         * number and unit alone; a kirana types 1 and "kg". Both are valid and
         * neither is assumed.
         */
        public String describe() {
            if (label != null && !label.isBlank()) {
                return label.trim();
            }
            if (quantity != null && unit != null && !unit.isBlank()) {
                return trimNumber(quantity) + " " + unit.trim();
            }
            if (unit != null && !unit.isBlank()) {
                return unit.trim();
            }
            return null;
        }

        private static String trimNumber(double value) {
            if (value == Math.rint(value) && !Double.isInfinite(value)) {
                return String.valueOf((long) value);
            }
            return String.valueOf(value);
        }
    }
}
