package com.gpstore.dto.request;

import jakarta.validation.constraints.NotNull;

/**
 * Client only ever supplies which product to wishlist - ownership (customer)
 * is always the logged-in caller, set server-side. Previously the controller
 * bound the raw Wishlist entity directly to the request body, which meant a
 * client could set the `product` relation (or any other entity field)
 * directly via JSON. This DTO closes that gap.
 */
public class WishlistRequest {

    @NotNull
    private Long productId;

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
}
