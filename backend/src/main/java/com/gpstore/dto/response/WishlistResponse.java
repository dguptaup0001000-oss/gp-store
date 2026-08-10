package com.gpstore.dto.response;

import com.gpstore.entity.Wishlist;

public class WishlistResponse {

    private final Long id;
    private final Long productId;
    private final String productName;

    public WishlistResponse(Long id, Long productId, String productName) {
        this.id = id;
        this.productId = productId;
        this.productName = productName;
    }

    public static WishlistResponse from(Wishlist wishlist) {
        var product = wishlist.getProduct();
        return new WishlistResponse(
                wishlist.getId(),
                product != null ? product.getId() : null,
                product != null ? product.getName() : null
        );
    }

    public Long getId() { return id; }
    public Long getProductId() { return productId; }
    public String getProductName() { return productName; }
}
