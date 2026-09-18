package com.gpstore.catalog.shop;

import com.gpstore.entity.ProductVariant;
import com.gpstore.entity.ProductVariantAttribute;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.ProductVariantAttributeRepository;
import com.gpstore.repository.ProductVariantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A shopkeeper edits an item on their OWN shelf.
 *
 * <p>THE ROUTE THAT DID NOT EXIST, and the reason a real merchant could not
 * save a price. The merchant admin app saved a variant through
 * {@code PUT /api/product-variants/{id}} - the PLATFORM's catalogue route,
 * guarded by CatalogDefinitionAuthorization. On a marketplace that needs
 * CATALOG_DEFINE, which only the platform holds, so Deepak Phone Shop
 * repricing his own Motorola got HTTP 403 and the app rendered it as
 * "Couldn't save variant - please check the values and try again". The values
 * were 35000/30000/29000 and there was never anything wrong with them.
 *
 * <p>Repricing your own shelf was never a platform act. This class is the
 * shopkeeper's half, and it is scoped the way every other shop write is: the
 * listing is found through the shop-scoped repository, so the shop is never a
 * parameter and a merchant naming another shop's variant finds nothing.
 *
 * <h2>Who may change what a product IS</h2>
 *
 * <p>The catalogue row is shared: two kiranas selling the same atta point at
 * one {@code product_variants} row, and its name, pack size and attributes are
 * what both of them sell. So a merchant may edit the catalogue half ONLY while
 * their shop is the only shop listing it - which is exactly the case for
 * something they created themselves, and exactly not the case for a shared
 * staple. The moment a second shop lists the same variant, the descriptive
 * fields freeze and the merchant keeps full control of the half that is
 * genuinely theirs: price, availability, stock, their own department.
 *
 * <p>That rule is not a convenience. Without it, the fix for "a merchant
 * cannot edit their own variant" would hand every merchant an edit box over
 * every other merchant's product description.
 */
@Service
public class ShopVariantEditing {

    private static final Logger log = LoggerFactory.getLogger(ShopVariantEditing.class);

    /** As many attributes as any real variant needs, and a bound on abuse. */
    public static final int MAX_ATTRIBUTES = 20;

    private final ShopProductVariantRepository listings;
    private final ProductVariantRepository variants;
    private final ProductVariantAttributeRepository attributes;
    private final ShopCategoryRepository shopCategories;
    private final ShopShelfCache shelfCache;

    public ShopVariantEditing(ShopProductVariantRepository listings,
                              ProductVariantRepository variants,
                              ProductVariantAttributeRepository attributes,
                              ShopCategoryRepository shopCategories,
                              ShopShelfCache shelfCache) {
        this.listings = listings;
        this.variants = variants;
        this.attributes = attributes;
        this.shopCategories = shopCategories;
        this.shelfCache = shelfCache;
    }

    /** What the merchant may send. Every field is optional but the price. */
    public record VariantEdit(String label,
                              Double quantity,
                              String unit,
                              String sku,
                              String barcode,
                              String imageUrl,
                              BigDecimal sellingPrice,
                              BigDecimal mrp,
                              BigDecimal costPrice,
                              Boolean available,
                              Boolean active,
                              Integer displayOrder,
                              Long shopCategoryId,
                              List<AttributeEdit> attributes) {
    }

    /** One name/value pair: {@code RAM = 8 GB}. */
    public record AttributeEdit(String name, String value) {
    }

    /** What goes back, including the attributes and this shop's own numbers. */
    public record VariantView(Long productVariantId,
                              String label,
                              Double quantity,
                              String unit,
                              BigDecimal sellingPrice,
                              BigDecimal mrp,
                              Boolean available,
                              Boolean active,
                              Long shopCategoryId,
                              boolean editableDescription,
                              List<AttributeEdit> attributes) {
    }

    // ------------------------------------------------------------------ read

    @Transactional(readOnly = true)
    public VariantView view(Long productVariantId) {
        ShopProductVariant listing = mine(productVariantId);
        ProductVariant variant = variants.findById(productVariantId)
                .orElseThrow(() -> new ResourceNotFoundException("That item is not in the catalogue."));
        return viewOf(listing, variant);
    }

    // ----------------------------------------------------------------- write

    /**
     * Saves what the merchant typed.
     *
     * <p>ONE TRANSACTION over both halves: this shop's listing row, and - when
     * this shop is the only one selling it - the catalogue row behind it. A
     * half-applied save is how a screen and a database start disagreeing.
     */
    @Transactional
    public VariantView update(Long productVariantId, VariantEdit edit) {
        ShopProductVariant listing = mine(productVariantId);
        ProductVariant variant = variants.findById(productVariantId)
                .orElseThrow(() -> new ResourceNotFoundException("That item is not in the catalogue."));

        BigDecimal selling = edit.sellingPrice() != null ? edit.sellingPrice() : listing.getSellingPrice();
        BigDecimal mrp = edit.mrp() != null ? edit.mrp() : listing.getMrp();
        BigDecimal cost = edit.costPrice() != null ? edit.costPrice() : listing.getCostPrice();
        validatePrices(selling, mrp, cost);

        listing.setSellingPrice(selling);
        listing.setMrp(mrp);
        listing.setCostPrice(cost);
        if (edit.available() != null) {
            listing.setAvailable(edit.available());
        }
        if (edit.active() != null) {
            listing.setActive(edit.active());
        }
        if (edit.displayOrder() != null) {
            listing.setDisplayOrder(edit.displayOrder());
        }
        if (edit.shopCategoryId() != null) {
            // Read through the shop-scoped repository, so a merchant cannot
            // file their listing under another shop's department.
            shopCategories.findById(edit.shopCategoryId()).orElseThrow(
                    () -> new BadRequestException("That department does not belong to this shop."));
            listing.setShopCategoryId(edit.shopCategoryId());
        }
        listings.save(listing);

        if (mayEditDescription(productVariantId)) {
            applyDescription(variant, edit);
            variants.save(variant);
            if (edit.attributes() != null) {
                replaceAttributes(productVariantId, edit.attributes());
            }
        } else if (hasDescriptionChange(edit)) {
            log.info("Shop declined a catalogue edit on variant {} - other shops list it too; "
                    + "the shop's own price and availability were still saved.", productVariantId);
        }

        // The storefront was serving the old price until the cache TTL drained.
        shelfCache.changed();
        return viewOf(listing, variant);
    }

    /**
     * A SECOND (or third) variant of something this shop already sells.
     *
     * <p>A phone comes in 8/128 and 12/256; a saree in red and in green. The
     * merchant had no way to add the second one: the only create route was the
     * platform's, which a shopkeeper cannot reach on a marketplace.
     *
     * <p>THE PRODUCT MUST ALREADY BE ON THIS SHELF. Checked through the
     * shop-scoped listing repository, so a merchant cannot bolt a variant onto
     * a product they do not sell - and gets 404 rather than 403, so which
     * product ids exist stays undiscoverable.
     */
    @Transactional
    public VariantView create(Long productId, VariantEdit edit) {
        if (productId == null) {
            throw new BadRequestException("Which product?");
        }
        List<ProductVariant> siblings = variants.findByProduct_IdOrderByIdAsc(productId);
        if (siblings.isEmpty()) {
            throw new ResourceNotFoundException("This shop does not sell that product.");
        }
        boolean onMyShelf = siblings.stream()
                .anyMatch(v -> listings.findByProductVariantId(v.getId()).isPresent());
        if (!onMyShelf) {
            throw new ResourceNotFoundException("This shop does not sell that product.");
        }

        validatePrices(edit.sellingPrice(), edit.mrp(), edit.costPrice());

        ProductVariant variant = new ProductVariant();
        variant.setProduct(siblings.get(0).getProduct());
        variant.setActive(Boolean.TRUE);
        variant.setAvailable(edit.available() == null ? Boolean.TRUE : edit.available());
        applyDescription(variant, edit);
        variant.setSellingPrice(edit.sellingPrice());
        variant.setMrp(edit.mrp());
        variant.setCostPrice(edit.costPrice());
        ProductVariant saved = variants.save(variant);

        ShopProductVariant listing = new ShopProductVariant();
        listing.setProductVariantId(saved.getId());
        listing.setSellingPrice(edit.sellingPrice());
        listing.setMrp(edit.mrp());
        listing.setCostPrice(edit.costPrice());
        listing.setAvailable(variant.getAvailable());
        listing.setActive(Boolean.TRUE);
        if (edit.shopCategoryId() != null) {
            shopCategories.findById(edit.shopCategoryId()).orElseThrow(
                    () -> new BadRequestException("That department does not belong to this shop."));
            listing.setShopCategoryId(edit.shopCategoryId());
        }
        // shop_id is stamped by TenantEntityListener from the resolved scope.
        listings.save(listing);

        if (edit.attributes() != null && !edit.attributes().isEmpty()) {
            replaceAttributes(saved.getId(), edit.attributes());
        }

        shelfCache.changed();
        return viewOf(listing, saved);
    }

    /**
     * Whether the caller's shop is the only one selling this catalogue item.
     *
     * <p>Counted across every shop on purpose - see
     * {@link ProductVariantAttributeRepository#countShopsListing}. The answer
     * is a number, so nothing about any other shop travels through it.
     */
    @Transactional(readOnly = true)
    public boolean mayEditDescription(Long productVariantId) {
        return attributes.countShopsListing(productVariantId) <= 1;
    }

    // ------------------------------------------------------------- internals

    /**
     * This shop's listing, or a 404.
     *
     * <p>NOT FOUND RATHER THAN FORBIDDEN, the same rule the rest of the
     * merchant surface follows: a merchant must not be able to discover which
     * variant ids other shops are selling by watching 403s turn into 404s.
     */
    private ShopProductVariant mine(Long productVariantId) {
        if (productVariantId == null) {
            throw new BadRequestException("Which item?");
        }
        return listings.findByProductVariantId(productVariantId).orElseThrow(
                () -> new ResourceNotFoundException("This shop does not list that item."));
    }

    private static boolean hasDescriptionChange(VariantEdit edit) {
        return edit.label() != null || edit.unit() != null || edit.quantity() != null
                || edit.sku() != null || edit.barcode() != null
                || (edit.attributes() != null && !edit.attributes().isEmpty());
    }

    private static void applyDescription(ProductVariant variant, VariantEdit edit) {
        if (edit.quantity() != null) {
            variant.setQuantity(edit.quantity());
        }
        // `unit` carries the free-text half: "kg" for atta, "8 GB + 128 GB"
        // for a phone. An explicit unit wins; otherwise the label fills it, so
        // a trade with no units at all still has something to show.
        if (edit.unit() != null && !edit.unit().isBlank()) {
            variant.setUnit(edit.unit().trim());
        } else if (edit.label() != null && !edit.label().isBlank()) {
            variant.setUnit(edit.label().trim());
        }
        if (edit.sku() != null) {
            variant.setSku(blankToNull(edit.sku()));
        }
        if (edit.barcode() != null) {
            variant.setBarcode(blankToNull(edit.barcode()));
        }
        if (edit.imageUrl() != null) {
            com.gpstore.catalog.CatalogUrlValidator.requireAllowedImageUrlOrEmpty(edit.imageUrl());
            variant.setImageUrl(com.gpstore.catalog.CatalogUrlValidator.trimToNull(edit.imageUrl()));
        }
        // The catalogue keeps its own default price so a SECOND shop listing
        // this item starts from a sensible number rather than from nothing.
        if (edit.sellingPrice() != null) {
            variant.setSellingPrice(edit.sellingPrice());
        }
        if (edit.mrp() != null) {
            variant.setMrp(edit.mrp());
        }
        if (edit.available() != null) {
            variant.setAvailable(edit.available());
        }
    }

    /**
     * The variant's attributes become exactly this list, in this order.
     *
     * <p>WHOLE-LIST REPLACEMENT, like the photo endpoint and for the same
     * reason: order is meaning ("RAM" before "Storage"), and three separate
     * add/remove/reorder calls are three chances for the screen and the server
     * to disagree about it. Re-sending the same list changes nothing, which is
     * what makes a retry after a dropped response safe.
     */
    private void replaceAttributes(Long productVariantId, List<AttributeEdit> wanted) {
        List<AttributeEdit> cleaned = clean(wanted);
        attributes.deleteByProductVariantId(productVariantId);
        // Flushed before the inserts so the unique index on
        // (product_variant_id, lower(name)) sees the deletes first - otherwise
        // re-sending the same list collides with the rows it is replacing.
        attributes.flush();
        int order = 0;
        List<ProductVariantAttribute> rows = new ArrayList<>(cleaned.size());
        for (AttributeEdit pair : cleaned) {
            ProductVariantAttribute row = new ProductVariantAttribute();
            row.setProductVariantId(productVariantId);
            row.setName(pair.name().trim());
            row.setValue(pair.value().trim());
            row.setDisplayOrder(order++);
            rows.add(row);
        }
        attributes.saveAll(rows);
    }

    /** Drops blanks and same-named duplicates, keeping the first of each. */
    private static List<AttributeEdit> clean(List<AttributeEdit> wanted) {
        List<AttributeEdit> cleaned = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (AttributeEdit pair : wanted) {
            if (pair == null || pair.name() == null || pair.name().isBlank()
                    || pair.value() == null || pair.value().isBlank()) {
                continue;
            }
            String name = pair.name().trim();
            String value = pair.value().trim();
            if (name.length() > 60) {
                throw new BadRequestException("Attribute name is too long: " + name);
            }
            if (value.length() > 160) {
                throw new BadRequestException("Attribute value is too long for " + name + ".");
            }
            if (!seen.add(name.toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            cleaned.add(new AttributeEdit(name, value));
        }
        if (cleaned.size() > MAX_ATTRIBUTES) {
            throw new BadRequestException(
                    "A variant can have at most " + MAX_ATTRIBUTES + " details.");
        }
        return cleaned;
    }

    /**
     * The price rules, and only the rules that are actually rules.
     *
     * <p>COST ABOVE SELLING IS NOT AN ERROR HERE. The platform route refuses it
     * unless the caller passes allowBelowCost, which is a sensible typo guard
     * for somebody entering a catalogue. For a shopkeeper repricing their own
     * shelf it is a decision they are allowed to make - a loss leader, or
     * clearing old stock - and a merchant should not have to discover a query
     * parameter to sell something at a loss. Their cost price is their own
     * private business either way.
     *
     * <p>SELLING ABOVE MRP IS refused: the maximum retail price is printed on
     * the packet and charging above it is the one price rule that is not the
     * merchant's to choose.
     */
    static void validatePrices(BigDecimal selling, BigDecimal mrp, BigDecimal cost) {
        if (selling == null || selling.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("Selling price must be more than zero.");
        }
        if (mrp != null && mrp.compareTo(BigDecimal.ZERO) < 0) {
            throw new BadRequestException("MRP cannot be negative.");
        }
        if (cost != null && cost.compareTo(BigDecimal.ZERO) < 0) {
            throw new BadRequestException("Cost price cannot be negative.");
        }
        if (mrp != null && mrp.compareTo(BigDecimal.ZERO) > 0
                && selling.compareTo(mrp) > 0) {
            throw new BadRequestException(
                    "Selling price cannot be above the MRP. Raise the MRP or lower the price.");
        }
    }

    private VariantView viewOf(ShopProductVariant listing, ProductVariant variant) {
        List<AttributeEdit> pairs = attributes
                .findByProductVariantIdOrderByDisplayOrderAscIdAsc(listing.getProductVariantId())
                .stream()
                .map(a -> new AttributeEdit(a.getName(), a.getValue()))
                .toList();
        return new VariantView(
                listing.getProductVariantId(),
                variant.getUnit(),
                variant.getQuantity(),
                variant.getUnit(),
                listing.getSellingPrice(),
                listing.getMrp(),
                listing.getAvailable(),
                listing.getActive(),
                listing.getShopCategoryId(),
                mayEditDescription(listing.getProductVariantId()),
                pairs);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** The variant's attributes, for anything that renders a variant. */
    @Transactional(readOnly = true)
    public List<AttributeEdit> attributesOf(Long productVariantId) {
        return attributes.findByProductVariantIdOrderByDisplayOrderAscIdAsc(productVariantId)
                .stream()
                .map(a -> new AttributeEdit(a.getName(), a.getValue()))
                .toList();
    }

    /** The listing this shop has for an item, when it has one. */
    @Transactional(readOnly = true)
    public Optional<ShopProductVariant> listingFor(Long productVariantId) {
        return listings.findByProductVariantId(productVariantId);
    }
}
