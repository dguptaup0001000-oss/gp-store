package com.gpstore.catalog.shop;

import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import com.gpstore.repository.InventoryRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How many of each variant THIS SHOP is actually holding, in one query.
 *
 * <p>WHY THE BROWSE PATHS NEED IT AT ALL. "This shop sells it" and "this shop
 * has any" are different questions with different answers, and the customer
 * screens were only ever told the first. So a kirana that had sold out of
 * 1 kg atta went on showing its price with an enabled ADD, and the customer
 * found out at the moment they tried to buy it - the add-to-cart path has
 * always refused (CartService.requireStockFor), which means the refusal was
 * real and the screen was the thing that was wrong.
 *
 * <p>The sibling of {@link ShopPricedCatalogue}, deliberately: same shape,
 * same batching, same reason. A twenty-product grid with five sizes each is
 * one query, not a hundred.
 *
 * <p>SHOP-SCOPED WITHOUT SAYING SO. The query is JPQL over Inventory, which is
 * shop-owned, so Hibernate's filter narrows it to the shop in scope. Nothing
 * here takes a shop id and nothing here could be asked for another shop's
 * stock by passing one.
 */
@Component
public class ShopStock {

    private final InventoryRepository inventory;

    public ShopStock(InventoryRepository inventory) {
        this.inventory = inventory;
    }

    /**
     * Stock for every variant of every product given, keyed by variant id.
     *
     * <p>A VARIANT WITH NO INVENTORY ROW IS REPORTED AS ZERO, not as absent.
     * "This shop has never counted it" and "this shop has none" are the same
     * thing to a customer trying to buy one, and reporting absence would let
     * the caller fall through to "stock unknown" - which renders as an
     * enabled ADD on an item that will be refused.
     */
    public Map<Long, Integer> heldFor(Collection<Product> products) {
        if (products == null || products.isEmpty()) {
            return Map.of();
        }
        Set<Long> variantIds = new LinkedHashSet<>();
        for (Product product : products) {
            List<ProductVariant> variants = product == null ? null : product.getVariants();
            if (variants == null) {
                continue;
            }
            for (ProductVariant variant : variants) {
                if (variant != null && variant.getId() != null) {
                    variantIds.add(variant.getId());
                }
            }
        }
        if (variantIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, Integer> held = new HashMap<>(variantIds.size());
        for (Long variantId : variantIds) {
            held.put(variantId, 0);
        }
        for (Object[] row : inventory.findStockByProductVariantIds(variantIds)) {
            Long variantId = ((Number) row[0]).longValue();
            Integer stock = row[1] == null ? 0 : ((Number) row[1]).intValue();
            held.put(variantId, stock);
        }
        return held;
    }

    /** The same, for one product. */
    public Map<Long, Integer> heldFor(Product product) {
        return product == null ? Map.of() : heldFor(List.of(product));
    }
}
