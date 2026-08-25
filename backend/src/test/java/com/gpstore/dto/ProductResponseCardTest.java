package com.gpstore.dto;

import com.gpstore.dto.response.ProductResponse;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProductResponseCardTest {

    @Test
    void fromCardKeepsTheCheapestAvailableVariant() {
        Product product = new Product();
        product.setId(9L);
        product.setName("Atta");
        product.setActive(true);
        product.setVariants(List.of(
                variant(1L, false, "10"),
                variant(2L, true, "90"),
                variant(3L, true, "40")));

        ProductResponse card = ProductResponse.fromCard(product);
        ProductResponse detail = ProductResponse.from(product);

        assertEquals(1, card.getVariants().size(), "a listing card must not serialise every pack size");
        assertEquals(3L, card.getVariants().get(0).getId(), "the cheapest in-stock size is the add-to-cart target");
        assertEquals(3, detail.getVariants().size(), "product detail still returns every variant");
    }

    @Test
    void fromCardIsNullSafe() {
        assertNull(ProductResponse.fromCard(null));
    }

    @Test
    void fromCardLeavesASingleVariantAlone() {
        Product product = new Product();
        product.setId(1L);
        product.setName("Salt");
        product.setActive(true);
        product.setVariants(List.of(variant(7L, true, "20")));

        ProductResponse card = ProductResponse.fromCard(product);
        assertEquals(1, card.getVariants().size());
        assertEquals(7L, card.getVariants().get(0).getId());
    }

    private static ProductVariant variant(Long id, boolean available, String price) {
        ProductVariant variant = new ProductVariant();
        variant.setId(id);
        variant.setAvailable(available);
        variant.setSellingPrice(new BigDecimal(price));
        variant.setUnit("kg");
        variant.setQuantity(1.0);
        return variant;
    }
}
