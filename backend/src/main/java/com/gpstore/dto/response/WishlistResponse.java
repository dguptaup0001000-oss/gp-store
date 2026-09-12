package com.gpstore.dto.response;

import com.gpstore.entity.Wishlist;

/**
 * A saved product, with the product itself attached.
 *
 * WHY THE NESTED PRODUCT EXISTS. This used to carry only productId and
 * productName, which broke the feature in two ways at once.
 *
 * The app's WishlistItem model expects a nested `product` object, so with a
 * flat DTO that field parsed as null on every item - and every downstream
 * check reads through it. isWishlisted() compares item.product?.id, so the
 * heart never filled; toggle() found no existing entry, so a second tap ADDED
 * again instead of removing; and My Wishlist rendered item.product, so it
 * looked empty even with rows in the database.
 *
 * The second problem would have survived merely renaming a field: a wishlist
 * screen has to draw a product card, and a card needs the image, the price
 * and the pack size. id and name cannot render one. Nesting the existing
 * ProductResponse gives the client exactly what every other product surface
 * already consumes, so no new model or mapper is needed anywhere.
 *
 * productId and productName are KEPT alongside it. They cost almost nothing,
 * and removing fields from a response is how you break a client that is
 * already installed on somebody's phone.
 */
public class WishlistResponse {

    private final Long id;
    private final Long productId;
    private final String productName;

    /**
     * Null only if the product was deleted out from under the wishlist row.
     * The client already treats it as nullable, so that degrades to a skipped
     * entry rather than a crash.
     */
    private final ProductResponse product;

    public WishlistResponse(Long id, Long productId, String productName, ProductResponse product) {
        this.id = id;
        this.productId = productId;
        this.productName = productName;
        this.product = product;
    }

    public static WishlistResponse from(Wishlist wishlist) {
        return from(wishlist, java.util.Map.of());
    }

    /** @param shopTerms this shop's price for each variant - see ProductResponse.fromCard. */
    public static WishlistResponse from(Wishlist wishlist,
            java.util.Map<Long, com.gpstore.catalog.shop.ShopProductVariant> shopTerms) {
        var product = wishlist.getProduct();
        return new WishlistResponse(
                wishlist.getId(),
                product != null ? product.getId() : null,
                product != null ? product.getName() : null,
                ProductResponse.fromCard(product, shopTerms)
        );
    }

    public Long getId() { return id; }
    public Long getProductId() { return productId; }
    public String getProductName() { return productName; }
    public ProductResponse getProduct() { return product; }
}
