package com.gpstore.catalog.shop;

import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a page of catalogue rows into "which variants do I need this shop's
 * price for", in one query.
 *
 * WHY IT IS ITS OWN THING. Every browse endpoint needs the same three lines -
 * collect the variant ids off the products, fetch this shop's listings, hand
 * the map to the mapper - and three lines copied into ten methods is three
 * lines that will be four in one of them. It also keeps the N+1 out: a
 * twenty-product page with five sizes each is one lookup, not a hundred.
 */
@Component
public class ShopPricedCatalogue {

    private final ShopCatalog shopCatalog;

    public ShopPricedCatalogue(ShopCatalog shopCatalog) {
        this.shopCatalog = shopCatalog;
    }

    /** This shop's terms for every variant of every product given. */
    public Map<Long, ShopProductVariant> termsFor(Collection<Product> products) {
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
        return shopCatalog.listingsFor(variantIds);
    }

    /** The same, for one product. */
    public Map<Long, ShopProductVariant> termsFor(Product product) {
        return product == null ? Map.of() : termsFor(List.of(product));
    }
}
