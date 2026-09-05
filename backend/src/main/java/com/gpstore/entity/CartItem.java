package com.gpstore.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "cart_items")
public class CartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cart_id")
    @JsonBackReference
    private Cart cart;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_variant_id")
    private ProductVariant productVariant;

    private Integer quantity;

    private BigDecimal price;

    private BigDecimal totalPrice;

    /**
     * Which shop's shelf this line came off.
     *
     * THE COLUMN THE SPLIT TURNS ON. At checkout the basket is grouped by it,
     * and each group becomes one shop's order - so a line's shop is decided
     * once, when it is added, and never re-derived from anything a caller
     * sends afterwards.
     *
     * NOT A TENANT BOUNDARY, and cart_items is deliberately NOT filtered by
     * it. A cart that could only show one shop's lines is not a multi-shop
     * cart: the customer would add something from the second kirana and watch
     * the first one's items vanish. What protects a cart is what always
     * protected it - it belongs to a customer, and CartService checks that on
     * every read and write.
     *
     * NEVER SENT BY A CLIENT. CartService stamps it from the shop the request
     * resolved to, which is why changing it in a request body cannot move an
     * item between shops.
     */
    @Column(name = "shop_id")
    private Long shopId;

    /**
     * Stamped here rather than in CartService, so EVERY path gets it.
     *
     * A service is one place a line can be created; an entity listener is all
     * of them. The first version of this stamped the shop in addToCart, which
     * was correct for the app and wrong for every other writer - the checkout
     * then refused baskets built any other way, which is a rule enforced
     * against the wrong thing.
     *
     * Same rule as every shop-owned row (TenantDefaults): the scope on the
     * thread if there is one, the single shop if there is only one, and a
     * refusal in a marketplace with neither.
     */
    @PrePersist
    void stampShop() {
        if (shopId == null) {
            shopId = com.gpstore.platform.TenantDefaults.shopIdForNewRow(null, CartItem.class);
        }
    }

    public CartItem() {
    }

    public Long getShopId() {
        return shopId;
    }

    public void setShopId(Long shopId) {
        this.shopId = shopId;
    }

    public Long getId() {
        return id;
    }

    public Cart getCart() {
        return cart;
    }

    public void setCart(Cart cart) {
        this.cart = cart;
    }

    public ProductVariant getProductVariant() {
        return productVariant;
    }

    public void setProductVariant(ProductVariant productVariant) {
        this.productVariant = productVariant;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getTotalPrice() {
        return totalPrice;
    }

    public void setTotalPrice(BigDecimal totalPrice) {
        this.totalPrice = totalPrice;
    }
}