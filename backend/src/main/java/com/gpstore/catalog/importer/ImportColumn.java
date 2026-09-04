package com.gpstore.catalog.importer;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The columns a catalogue sheet may contain, mapped to fields that actually
 * exist on Product / ProductVariant / Inventory.
 *
 * DELIBERATELY NOT A COPY OF THE REQUESTED COLUMN LIST. Two columns people
 * ask for have nowhere to go in this schema, and inventing storage for them
 * would be worse than saying so:
 *
 *   Discount - there is no discount field. A discount here is MRP minus
 *              selling price. The column is ACCEPTED as a CROSS-CHECK: if it
 *              disagrees with the two prices, the row is flagged rather than
 *              silently trusted, which catches the common spreadsheet error
 *              of editing one price and forgetting the other.
 *
 *   New      - "new arrival" is derived from created_at, so a column claiming
 *              it would either do nothing or require back-dating a product.
 *              It is rejected with an explanation instead of ignored.
 *
 * COST PRICE is included even though it was not asked for: free-delivery
 * eligibility is computed from margin, and production orders are already
 * carrying the warning "No cost price is recorded for N item(s), so they
 * contributed no margin". A bulk importer that cannot set it guarantees that
 * warning stays.
 */
public enum ImportColumn {

    SKU("SKU", "sku", "variant sku"),
    BARCODE("Barcode", "barcode", "ean", "upc"),
    PRODUCT_NAME("Product Name", "name", "product", "productname"),
    BRAND("Brand", "brand"),
    CATEGORY("Category", "category"),
    SUBCATEGORY("Subcategory", "sub category", "sub-category"),
    VARIANT_VALUE("Variant Value", "variant", "variant name", "quantity", "size", "pack size"),
    UNIT("Unit", "uom", "units"),
    MRP("MRP", "mrp", "list price", "maximum retail price"),
    SELLING_PRICE("Selling Price", "price", "sale price", "sellingprice"),
    COST_PRICE("Cost Price", "cost", "purchase price"),
    DISCOUNT("Discount", "discount", "discount %", "discount percent"),
    STOCK("Stock", "stock", "quantity in stock", "qty"),
    LOW_STOCK_THRESHOLD("Low Stock Threshold", "low stock", "minimum stock", "reorder level"),
    WEIGHT_GRAMS("Weight Grams", "weight", "weight (g)", "weight g"),
    GST_RATE("GST Rate", "gst", "gst %", "tax rate"),
    DESCRIPTION("Description", "description", "details"),
    IMAGE_1("Image 1", "image1", "image", "primary image", "thumbnail"),
    IMAGE_2("Image 2", "image2"),
    IMAGE_3("Image 3", "image3"),
    IMAGE_4("Image 4", "image4"),
    IMAGE_5("Image 5", "image5"),
    FEATURED("Featured", "featured"),
    BESTSELLER("Bestseller", "bestseller", "best seller"),
    ACTIVE("Active", "active", "enabled");

    private final String canonical;
    private final String[] aliases;

    ImportColumn(String canonical, String... aliases) {
        this.canonical = canonical;
        this.aliases = aliases;
    }

    public String canonical() {
        return canonical;
    }

    /** Columns a template hands out, in the order a person expects to fill them. */
    public static ImportColumn[] templateOrder() {
        return values();
    }

    /**
     * Columns that name a field this importer refuses to pretend it can set.
     * Matched so the admin gets an explanation rather than silence.
     */
    private static final Map<String, String> UNSUPPORTED = new LinkedHashMap<>();
    static {
        UNSUPPORTED.put("new", "\"New\" is worked out from when a product was added, "
                + "so it cannot be set from a sheet. Remove the column.");
        UNSUPPORTED.put("new product", "\"New\" is worked out from when a product was added, "
                + "so it cannot be set from a sheet. Remove the column.");
        UNSUPPORTED.put("shop", "GP-STORE runs as a single shop today, so there is no shop "
                + "column to fill in. Remove it.");
        UNSUPPORTED.put("store", "GP-STORE runs as a single shop today, so there is no store "
                + "column to fill in. Remove it.");
    }

    public static String unsupportedReason(String header) {
        return UNSUPPORTED.get(normalise(header));
    }

    /**
     * Header matching that survives a real spreadsheet: different case, extra
     * spaces, underscores, a stray BOM from Excel's CSV export, and the
     * alternative names people actually type.
     */
    public static ImportColumn match(String header) {
        String needle = normalise(header);
        if (needle.isEmpty()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(column -> normalise(column.canonical).equals(needle)
                        || Arrays.stream(column.aliases).anyMatch(a -> normalise(a).equals(needle)))
                .findFirst()
                .orElse(null);
    }

    static String normalise(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("﻿", "")          // Excel's UTF-8 BOM
                .replace('_', ' ')
                .replace('-', ' ')
                .trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }
}
