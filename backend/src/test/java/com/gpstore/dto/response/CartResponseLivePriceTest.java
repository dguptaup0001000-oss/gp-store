package com.gpstore.dto.response;

import com.gpstore.entity.Cart;
import com.gpstore.entity.CartItem;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CartResponseLivePriceTest {

    @Test
    void cartTotalUsesLiveSellingPriceNotTheStoredAddTimePrice() {
        CartResponse response = CartResponse.from(cartWithLine(
                new BigDecimal("100"), new BigDecimal("80"), 2), Map.of());

        assertEquals(0, new BigDecimal("160").compareTo(response.getTotalAmount()));
        assertEquals(0, new BigDecimal("80").compareTo(response.getItems().getFirst().getPrice()));
    }

    @Test
    void lineIsUnavailableWhenStockIsBelowQuantity() {
        CartResponse response = CartResponse.from(cartWithLine(
                new BigDecimal("80"), new BigDecimal("80"), 3), Map.of(9L, 2));

        assertFalse(response.getItems().getFirst().getAvailable());
    }

    @Test
    void lineIsAvailableWhenStockCoversQuantity() {
        CartResponse response = CartResponse.from(cartWithLine(
                new BigDecimal("80"), new BigDecimal("80"), 2), Map.of(9L, 2));

        assertTrue(response.getItems().getFirst().getAvailable());
    }

    private static Cart cartWithLine(BigDecimal storedPrice, BigDecimal livePrice, int quantity) {
        Product product = new Product();
        product.setName("Test dal");
        product.setActive(true);

        ProductVariant variant = new ProductVariant();
        variant.setId(9L);
        variant.setProduct(product);
        variant.setAvailable(true);
        variant.setSellingPrice(livePrice);

        CartItem item = new CartItem();
        item.setId(1L);
        item.setProductVariant(variant);
        item.setQuantity(quantity);
        item.setPrice(storedPrice);
        item.setTotalPrice(storedPrice.multiply(BigDecimal.valueOf(quantity)));

        Cart cart = new Cart();
        cart.setId(1L);
        cart.setItems(new ArrayList<>());
        cart.getItems().add(item);
        return cart;
    }
}
