package com.gpstore.dto.response;

import com.gpstore.entity.Cart;
import com.gpstore.entity.CartItem;

import java.math.BigDecimal;
import java.util.List;

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
        if (cart == null) {
            return new CartResponse(null, List.of(), BigDecimal.ZERO, 0);
        }

        List<CartItemResponse> items = cart.getItems().stream()
                .map(CartItemResponse::from)
                .toList();

        // Derived live from the actual items list rather than trusting
        // cart.getTotalItems()/getTotalAmount() - those denormalized columns
        // are only as correct as whatever write path last touched them, and
        // any cart row left over from before a write-path bug was fixed
        // (e.g. the old clearCart() that deleted items without zeroing these
        // columns) would otherwise keep showing stale totals forever, since
        // nothing re-derives them until the next add/update/remove. Computing
        // straight from `items` here makes the response self-correcting on
        // every read, independent of how the row got out of sync.
        int totalItems = cart.getItems().stream().mapToInt(CartItem::getQuantity).sum();
        BigDecimal totalAmount = cart.getItems().stream()
                .map(CartItem::getTotalPrice)
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

            return new CartItemResponse(
                    item.getId(),
                    product != null ? product.getId() : null,
                    product != null ? product.getName() : null,
                    product != null ? product.getBrand() : null,
                    variant != null ? variant.getId() : null,
                    item.getQuantity(),
                    variant != null ? variant.getQuantity() : null,
                    variant != null ? variant.getUnit() : null,
                    variant != null ? variant.getImageUrl() : null,
                    item.getPrice(),
                    item.getTotalPrice(),
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
