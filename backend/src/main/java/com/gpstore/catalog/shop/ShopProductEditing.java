package com.gpstore.catalog.shop;

import com.gpstore.entity.Category;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.CategoryRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ProductVariantAttributeRepository;
import com.gpstore.repository.ProductVariantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * A shopkeeper edits the PRODUCT behind their own shelf.
 *
 * <p>THE HALF THAT WAS STILL MISSING. Variants, photos and departments were
 * moved onto the merchant's own surface; the product itself was not. So Edit
 * Product → Save Changes still went to {@code PUT /api/products/{id}}, the
 * platform catalogue route, and a merchant editing the phone he had created
 * himself got - captured from the running backend -
 *
 * <pre>PUT /api/products/3428 -> 403 "You don't have permission to do that."</pre>
 *
 * <p>The merchant could READ the product (his shop lists it, so it is on his
 * shelf) and could not WRITE it. That contradiction is what this class closes,
 * and it closes it WITHOUT giving anybody catalogue-wide authority.
 *
 * <h2>Two different things on one screen</h2>
 *
 * <p>The Edit Product screen mixes data that belongs to two owners, and the
 * fix is to route each half to its owner rather than to pick one:
 *
 * <ul>
 *   <li><b>Active</b> is THIS SHOP'S. A merchant turning a product off means
 *       "stop selling it in my shop", not "withdraw it from GP-STORE". It is
 *       applied to this shop's own listing rows and touches no other shop.</li>
 *   <li><b>Name, brand, category</b> are the CENTRAL CATALOGUE's. They are
 *       what every shop selling this product shows, so a merchant may change
 *       them only while their shop is the only one listing it - the same
 *       sole-lister rule {@link ShopVariantEditing} applies to a variant's
 *       description, for the same reason. When another shop also sells it the
 *       merchant is told so plainly instead of being refused with "you don't
 *       have permission", which was never what was wrong.</li>
 * </ul>
 */
@Service
public class ShopProductEditing {

    private final ShopProductVariantRepository listings;
    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final ProductVariantAttributeRepository counts;
    private final CategoryRepository categories;
    private final ShopShelfCache shelfCache;

    public ShopProductEditing(ShopProductVariantRepository listings,
                              ProductRepository products,
                              ProductVariantRepository variants,
                              ProductVariantAttributeRepository counts,
                              CategoryRepository categories,
                              ShopShelfCache shelfCache) {
        this.listings = listings;
        this.products = products;
        this.variants = variants;
        this.counts = counts;
        this.categories = categories;
        this.shelfCache = shelfCache;
    }

    /** What the merchant may send from Edit Product. Every field optional. */
    public record ProductEdit(String name, String brand, Long categoryId, Boolean active) {
    }

    public record ProductView(Long id, String name, String brand, Long categoryId,
                              Boolean activeInThisShop, boolean editableCatalogue) {
    }

    @Transactional(readOnly = true)
    public ProductView view(Long productId) {
        Product product = mine(productId);
        return viewOf(product);
    }

    /**
     * Saves what the merchant typed on Edit Product.
     *
     * <p>ONE TRANSACTION over this shop's listing rows and - when this shop is
     * the only one selling the product - the catalogue row behind them.
     */
    @Transactional
    public ProductView update(Long productId, ProductEdit edit) {
        Product product = mine(productId);
        List<ShopProductVariant> mine = listingsOf(productId);

        if (edit.active() != null) {
            // THIS SHOP'S SHELF, not the platform's catalogue. Turning a
            // product off here delists it here and nowhere else.
            for (ShopProductVariant listing : mine) {
                listing.setActive(edit.active());
                listing.setAvailable(edit.active());
            }
            listings.saveAll(mine);
        }

        boolean wantsCatalogueChange = edit.name() != null
                || edit.brand() != null
                || edit.categoryId() != null;

        if (wantsCatalogueChange) {
            if (!mayEditCatalogue(productId)) {
                // NOT "you don't have permission". The merchant has every
                // permission a shopkeeper has; this product is simply also
                // somebody else's stock, and saying so is the difference
                // between an answer and a brush-off.
                throw new BadRequestException(
                        "Other shops also sell this product, so its name, brand and category "
                                + "are managed by GP-STORE. Your own price, stock, photos and "
                                + "availability are still yours to change.");
            }
            if (edit.name() != null) {
                String name = edit.name().trim();
                if (name.isEmpty()) {
                    throw new BadRequestException("A product needs a name.");
                }
                if (name.length() > 255) {
                    throw new BadRequestException("That product name is too long.");
                }
                product.setName(name);
            }
            if (edit.brand() != null) {
                product.setBrand(blankToNull(edit.brand()));
            }
            if (edit.categoryId() != null) {
                Category category = categories.findById(edit.categoryId()).orElseThrow(
                        () -> new BadRequestException("That category does not exist."));
                product.setCategory(category);
            }
            products.save(product);
        }

        shelfCache.changed();
        return viewOf(product);
    }

    /**
     * Whether this shop is the only one selling the product.
     *
     * <p>Counted across every shop on purpose - the question is explicitly
     * cross-shop, and the answer is a number, so nothing about any other shop
     * travels through it.
     */
    @Transactional(readOnly = true)
    public boolean mayEditCatalogue(Long productId) {
        return counts.countShopsListingProduct(productId) <= 1;
    }

    // ------------------------------------------------------------- internals

    /**
     * The product, but only if THIS shop actually sells it.
     *
     * <p>READ THROUGH THE SHOP-SCOPED LISTING REPOSITORY, which is the same
     * authoritative relationship the merchant's Products list is built from
     * (ProductResponse.forShopAdmin keeps only variants this shop has a
     * listing for). Read and write therefore agree by construction: anything
     * the merchant can see on that screen, they can open here, and anything
     * they cannot see is a 404 rather than a 403 - so which product ids exist
     * stays undiscoverable.
     */
    private Product mine(Long productId) {
        if (productId == null) {
            throw new BadRequestException("Which product?");
        }
        if (listingsOf(productId).isEmpty()) {
            throw new ResourceNotFoundException("This shop does not sell that product.");
        }
        return products.findById(productId).orElseThrow(
                () -> new ResourceNotFoundException("That product is not in the catalogue."));
    }

    /** This shop's listing rows for every variant of one product. */
    private List<ShopProductVariant> listingsOf(Long productId) {
        List<Long> variantIds = variants.findByProduct_IdOrderByIdAsc(productId)
                .stream().map(ProductVariant::getId).toList();
        if (variantIds.isEmpty()) {
            return List.of();
        }
        // Shop-scoped: the filter narrows this to the shop in scope, so a
        // product another shop sells comes back with no rows at all.
        return listings.findByProductVariantIdIn(variantIds);
    }

    private ProductView viewOf(Product product) {
        List<ShopProductVariant> mine = listingsOf(product.getId());
        boolean activeHere = mine.stream().anyMatch(l -> Boolean.TRUE.equals(l.getActive()));
        return new ProductView(
                product.getId(),
                product.getName(),
                product.getBrand(),
                product.getCategory() == null ? null : product.getCategory().getId(),
                activeHere,
                mayEditCatalogue(product.getId()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
