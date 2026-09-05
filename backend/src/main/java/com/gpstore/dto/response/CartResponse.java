package com.gpstore.dto.response;

import com.gpstore.entity.Cart;
import com.gpstore.entity.CartItem;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Explicit response shape instead of returning the raw Cart entity directly.
 * Needed because ProductVariant.product is deliberately excluded from JSON
 * (it's the @JsonBackReference side, preventing the Cart<->CartItem
 * recursion bug fixed alongside this) - which means the raw entity
 * genuinely cannot tell a client the product's name, only variant details.
 * This DTO fills that gap explicitly rather than leaving it missing.
 */
public class CartResponse {

    private final Long cartId;
    private final List<CartItemResponse> items;
    private final BigDecimal totalAmount;
    private final Integer totalItems;

    public CartResponse(Long cartId, List<CartItemResponse> items, BigDecimal totalAmount, Integer totalItems) {
        this.cartId = cartId;
        this.items = items;
        this.totalAmount = totalAmount;
        this.totalItems = totalItems;
    }

    public static CartResponse from(Cart cart) {
        return from(cart, Map.of(), Map.of());
    }

    /** Kept for callers with no shop price list - the line's own stored price stands. */
    public static CartResponse from(Cart cart, Map<Long, Integer> stockByVariantId) {
        return from(cart, stockByVariantId, Map.of());
    }

    /**
     * @param stockByVariantId live inventory for the customer's cart. A
     *                         missing key means "do not apply a stock gate"
     *                         (admin listings). A present value is compared
     *                         to the line quantity.
     */
    public static CartResponse from(Cart cart, Map<Long, Integer> stockByVariantId,
                                    Map<Long, BigDecimal> shopPriceByVariantId) {
        if (cart == null) {
            return new CartResponse(null, List.of(), BigDecimal.ZERO, 0);
        }

        Map<Long, Integer> stock = stockByVariantId == null ? Map.of() : stockByVariantId;
        Map<Long, BigDecimal> prices = shopPriceByVariantId == null ? Map.of() : shopPriceByVariantId;
        List<CartItemResponse> items = cart.getItems().stream()
                .map(item -> CartItemResponse.from(item, stock, prices))
                .toList();

        // Derived live from the mapped lines so a catalog price change
        // shows on the next cart GET without waiting for a mutation.
        int totalItems = items.stream().mapToInt(CartItemResponse::getQuantity).sum();
        BigDecimal totalAmount = items.stream()
                .map(CartItemResponse::getTotalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new CartResponse(cart.getId(), items, totalAmount, totalItems);
    }

    public Long getCartId() { return cartId; }
    public List<CartItemResponse> getItems() { return items; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public Integer getTotalItems() { return totalItems; }

    public static class CartItemResponse {
        private final Long cartItemId;
        private final Long productId;
        private final String productName;
        private final String productBrand;
        private final Long variantId;
        private final Integer quantity;
        private final Double variantQuantity;
        private final String unit;
        private final String imageUrl;
        private final BigDecimal price;
        private final BigDecimal totalPrice;
        private final BigDecimal mrp;
        private final Boolean available;

        private CartItemResponse(Long cartItemId, Long productId, String productName, String productBrand,
                                  Long variantId, Integer quantity, Double variantQuantity, String unit, String imageUrl,
                                  BigDecimal price, BigDecimal totalPrice, BigDecimal mrp, Boolean available) {
            this.cartItemId = cartItemId;
            this.productId = productId;
            this.productName = productName;
            this.productBrand = productBrand;
            this.variantId = variantId;
            this.quantity = quantity;
            this.variantQuantity = variantQuantity;
            this.unit = unit;
            this.imageUrl = imageUrl;
            this.price = price;
            this.totalPrice = totalPrice;
            this.mrp = mrp;
            this.available = available;
        }

        static CartItemResponse from(CartItem item) {
            return from(item, Map.of(), Map.of());
        }

        static CartItemResponse from(CartItem item, Map<Long, Integer> stockByVariantId,
                                     Map<Long, BigDecimal> shopPriceByVariantId) {
            var variant = item.getProductVariant();
            var product = variant != null ? variant.getProduct() : null;

            // Matches OrderService.placeOrder's checkout validation exactly -
            // a product can be made unsellable at either level (the whole
            // product deactivated, or just this one variant), and both
            // need to show here, not just the variant-level one. Checking
            // only variant.getAvailable() would miss a whole product being
            // deactivated, showing it as available right up until checkout
            // rejected it - the exact "surprise at the last step" this
            // field exists to prevent.
            boolean isAvailable = variant != null
                    && Boolean.TRUE.equals(variant.getAvailable())
                    && product != null
                    && Boolean.TRUE.equals(product.getActive());
            if (isAvailable && variant.getId() != null && stockByVariantId.containsKey(variant.getId())) {
                Integer stock = stockByVariantId.get(variant.getId());
                isAvailable = stock != null && stock >= item.getQuantity();
            }

            // WHAT THIS SHOP CHARGES, then what the line was saved at, then the
            // catalogue default. The shop's own price wins because it is the
            // one checkout will use, and a cart that shows a different number
            // from the one charged is the worst of the three failures here.
            BigDecimal shopPrice = variant == null ? null : shopPriceByVariantId.get(variant.getId());
            BigDecimal unitPrice = shopPrice != null
                    ? shopPrice
                    : (variant != null && variant.getSellingPrice() != null
                            ? variant.getSellingPrice()
                            : item.getPrice());
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(item.getQuantity()));

            return new CartItemResponse(
                    item.getId(),
                    product != null ? product.getId() : null,
                    // Privacy-safe: a cart is the screen most likely to be
                    // visible to someone standing next to the customer, and
                    // the real name is never needed to render it. The product
                    // ID below is the real one, so inventory and checkout are
                    // unaffected - see Product.customerFacingName().
                    product != null ? product.customerFacingName() : null,
                    product != null ? product.getBrand() : null,
                    variant != null ? variant.getId() : null,
                    item.getQuantity(),
                    variant != null ? variant.getQuantity() : null,
                    variant != null ? variant.getUnit() : null,
                    variant != null && variant.getImageUrl() != null
                            ? com.gpstore.upload.CatalogImageDelivery.forClient(variant.getImageUrl())
                            : null,
                    unitPrice,
                    lineTotal,
                    variant != null ? variant.getMrp() : null,
                    isAvailable
            );
        }

        public Long getCartItemId() { return cartItemId; }
        public Long getProductId() { return productId; }
        public String getProductName() { return productName; }
        public String getProductBrand() { return productBrand; }
        public Long getVariantId() { return variantId; }
        public Integer getQuantity() { return quantity; }
        public Double getVariantQuantity() { return variantQuantity; }
        public String getUnit() { return unit; }
        public String getImageUrl() { return imageUrl; }
        public BigDecimal getPrice() { return price; }
        public BigDecimal getTotalPrice() { return totalPrice; }
        public BigDecimal getMrp() { return mrp; }
        public Boolean getAvailable() { return available; }
    }
}
