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

    @Test
    void fromCardStillSaysHowManySizesTheProductHas() {
        // THE POINT OF THE FIELD. The card carries one variant to keep a feed
        // page small, and before this the app could not tell a one-size
        // product from a five-size one - so the grid added the cheapest pack
        // on tap and never offered the others.
        Product product = new Product();
        product.setId(9L);
        product.setName("Atta");
        product.setActive(true);
        product.setVariants(List.of(
                variant(1L, false, "10"),
                variant(2L, true, "90"),
                variant(3L, true, "40")));

        ProductResponse card = ProductResponse.fromCard(product);

        assertEquals(1, card.getVariants().size(), "still trimmed");
        assertEquals(3, card.getVariantCount(),
                "the card must report every size the product has, not the one it carries");
    }

    @Test
    void anUntrimmedResponseCountsItsOwnVariants() {
        // Nothing has to remember to set the count on the ordinary paths: the
        // constructor derives it from the list, so detail responses and the
        // admin payloads cannot drift from what they actually serialise.
        Product product = new Product();
        product.setId(4L);
        product.setName("Salt");
        product.setActive(true);
        product.setVariants(List.of(variant(7L, true, "20"), variant(8L, true, "35")));

        assertEquals(2, ProductResponse.from(product).getVariantCount());
    }

    @Test
    void aSingleVariantCardReportsOneSize() {
        Product product = new Product();
        product.setId(1L);
        product.setName("Salt");
        product.setActive(true);
        product.setVariants(List.of(variant(7L, true, "20")));

        assertEquals(1, ProductResponse.fromCard(product).getVariantCount());
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
