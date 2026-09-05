package com.gpstore.catalog.importer;

import com.gpstore.catalog.CatalogUrlValidator;
import com.gpstore.catalog.importer.CatalogImportProblem.Severity;
import com.gpstore.catalog.importer.CatalogImportRun.Mode;
import com.gpstore.catalog.importer.CatalogSheetReader.ImportSheet;
import com.gpstore.catalog.importer.CatalogSheetReader.SheetRow;
import com.gpstore.entity.Category;
import com.gpstore.entity.ProductVariant;
import com.gpstore.repository.CategoryRepository;
import com.gpstore.repository.ProductVariantRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * Decides what a catalogue sheet is allowed to do, before anything is written.
 *
 * THE RULE THIS FILE EXISTS FOR: an importer that silently accepts corrupt
 * data is worse than no importer. A shop that types 5600 instead of 56.00 in
 * one cell and gets told "947 imported" finds out from a customer.
 *
 * ERROR means the row is not imported. WARNING means it is imported but
 * something looks wrong enough to read before trusting it. The difference is
 * deliberate and narrow: anything that would store a WRONG NUMBER is an error;
 * anything that is merely unusual is a warning.
 */
@Component
public class CatalogImportValidator {

    /**
     * Units the catalogue actually uses (kg, g, l, ml, pcs are already in the
     * data) plus the ordinary retail ones. Extensible by design - adding a
     * unit is one line here, and rejecting an unknown one is what stops "kgs",
     * "Kg." and "kilogram" becoming three different units nobody can filter on.
     */
    private static final Map<String, String> UNITS = new LinkedHashMap<>();
    static {
        for (String u : new String[]{"g", "kg", "ml", "l", "pcs", "pack", "box",
                "bottle", "dozen", "packet", "jar", "tin", "can", "sachet", "bundle"}) {
            UNITS.put(u, u);
        }
        // What people actually type, mapped onto the canonical form above.
        UNITS.put("gram", "g"); UNITS.put("grams", "g"); UNITS.put("gm", "g"); UNITS.put("gms", "g");
        UNITS.put("kilogram", "kg"); UNITS.put("kilograms", "kg"); UNITS.put("kgs", "kg");
        UNITS.put("litre", "l"); UNITS.put("liter", "l"); UNITS.put("litres", "l"); UNITS.put("ltr", "l");
        UNITS.put("millilitre", "ml"); UNITS.put("milliliter", "ml"); UNITS.put("mls", "ml");
        UNITS.put("piece", "pcs"); UNITS.put("pieces", "pcs"); UNITS.put("pc", "pcs"); UNITS.put("nos", "pcs");
    }

    /**
     * A price nobody in a kirana shop means. Its job is to catch a misplaced
     * decimal or a pasted phone number, not to cap what the shop may charge.
     */
    private static final BigDecimal MAX_MONEY = new BigDecimal("1000000");
    private static final int MAX_STOCK = 1_000_000;

    private final ProductVariantRepository variants;
    private final CategoryRepository categories;

    public CatalogImportValidator(ProductVariantRepository variants, CategoryRepository categories) {
        this.variants = variants;
        this.categories = categories;
    }

    /** One row, type-checked, with only the columns the sheet actually had. */
    public record PlannedRow(
            int rowNumber,
            String sku,
            Map<ImportColumn, Object> values,
            /** True when this SKU does not exist yet and the row would create it. */
            boolean creates) {
    }

    public record Outcome(
            List<CatalogImportProblem> problems,
            List<PlannedRow> importable,
            int totalRows, int validRows, int warningRows, int errorRows) {
    }

    public Outcome validate(ImportSheet sheet, Mode mode) {
        List<CatalogImportProblem> problems = new ArrayList<>();
        List<PlannedRow> importable = new ArrayList<>();

        checkHeaders(sheet, problems);

        // Whole-file checks need memory of what has been seen.
        Map<String, Integer> skuFirstSeen = new HashMap<>();
        Map<String, Integer> barcodeFirstSeen = new HashMap<>();
        Map<String, Integer> identicalRowFirstSeen = new HashMap<>();

        Set<Integer> rowsWithError = new HashSet<>();
        Set<Integer> rowsWithWarning = new HashSet<>();

        for (SheetRow row : sheet.rows()) {
            List<CatalogImportProblem> rowProblems = new ArrayList<>();
            Map<ImportColumn, Object> typed = new EnumMap<>(ImportColumn.class);

            String sku = validateSku(row, rowProblems, skuFirstSeen);
            boolean exists = sku != null && variants.findBySku(sku).isPresent();

            validateMode(row, mode, sku, exists, rowProblems);
            validateIdentity(row, mode, exists, rowProblems, typed);
            validateBarcode(row, sku, rowProblems, typed, barcodeFirstSeen);
            validateCategory(row, rowProblems, typed);
            validateVariant(row, rowProblems, typed);
            validatePrices(row, rowProblems, typed);
            validateStock(row, rowProblems, typed);
            validateImages(row, rowProblems, typed);
            validateFlags(row, rowProblems, typed);
            validateText(row, rowProblems, typed);
            noteDuplicateRow(row, rowProblems, identicalRowFirstSeen);

            boolean hasError = rowProblems.stream().anyMatch(p -> p.getSeverity() == Severity.ERROR);
            if (hasError) {
                rowsWithError.add(row.rowNumber());
            } else {
                if (rowProblems.stream().anyMatch(p -> p.getSeverity() == Severity.WARNING)) {
                    rowsWithWarning.add(row.rowNumber());
                }
                importable.add(new PlannedRow(row.rowNumber(), sku, typed, !exists));
            }
            problems.addAll(rowProblems);
        }

        int total = sheet.rows().size();
        int errors = rowsWithError.size();
        int warnings = rowsWithWarning.size();
        return new Outcome(problems, importable, total, total - errors - warnings, warnings, errors);
    }

    // ------------------------------------------------------------- headers

    private void checkHeaders(ImportSheet sheet, List<CatalogImportProblem> problems) {
        for (String header : sheet.unknownHeaders()) {
            String reason = ImportColumn.unsupportedReason(header);
            problems.add(new CatalogImportProblem(1, header,
                    reason != null ? Severity.ERROR : Severity.WARNING,
                    reason != null ? reason : "This column is not recognised and was ignored.",
                    reason != null ? null
                            : "Rename it to one of the template's columns, or remove it."));
        }
        if (!sheet.presentColumns().contains(ImportColumn.SKU)) {
            problems.add(new CatalogImportProblem(1, "SKU", Severity.ERROR,
                    "The sheet has no SKU column, so there is no way to tell which product "
                            + "each row is about.",
                    "Download the template and copy your data into it."));
        }
    }

    // ------------------------------------------------------------ per row

    private String validateSku(SheetRow row, List<CatalogImportProblem> problems,
                               Map<String, Integer> firstSeen) {
        String sku = row.get(ImportColumn.SKU);
        if (sku == null || sku.isBlank()) {
            problems.add(problem(row, "SKU", Severity.ERROR,
                    "SKU is empty.", "Every row needs the code that identifies the product."));
            return null;
        }
        if (sku.length() > 64) {
            problems.add(problem(row, "SKU", Severity.ERROR,
                    "SKU is longer than 64 characters.", "Shorten it."));
            return null;
        }
        Integer seenAt = firstSeen.putIfAbsent(sku.toLowerCase(Locale.ROOT), row.rowNumber());
        if (seenAt != null) {
            problems.add(problem(row, "SKU", Severity.ERROR,
                    "SKU \"" + sku + "\" already appears on row " + seenAt + " of this file.",
                    "Two rows for the same product contradict each other - keep one."));
            return null;
        }
        return sku;
    }

    private void validateMode(SheetRow row, Mode mode, String sku, boolean exists,
                              List<CatalogImportProblem> problems) {
        if (mode == Mode.UPDATE_ONLY && sku != null && !exists) {
            problems.add(problem(row, "SKU", Severity.ERROR,
                    "No product has SKU \"" + sku + "\", and this is an update-only import.",
                    "Check the code for a typo, or run a full import if it really is new."));
        }
    }

    private void validateIdentity(SheetRow row, Mode mode, boolean exists,
                                  List<CatalogImportProblem> problems,
                                  Map<ImportColumn, Object> typed) {
        String name = row.get(ImportColumn.PRODUCT_NAME);
        boolean creating = mode == Mode.IMPORT && !exists;

        if (creating && (name == null || name.isBlank())) {
            problems.add(problem(row, "Product Name", Severity.ERROR,
                    "A new product needs a name.",
                    "Fill in Product Name, or use an existing SKU to update instead."));
        }
        if (name != null && !name.isBlank()) {
            if (name.length() > 255) {
                problems.add(problem(row, "Product Name", Severity.ERROR,
                        "Product Name is longer than 255 characters.", "Shorten it."));
            } else {
                typed.put(ImportColumn.PRODUCT_NAME, name);
            }
        }
        if (row.has(ImportColumn.BRAND)) {
            typed.put(ImportColumn.BRAND, emptyToNull(row.get(ImportColumn.BRAND)));
        }
        if (row.has(ImportColumn.SUBCATEGORY)) {
            typed.put(ImportColumn.SUBCATEGORY, emptyToNull(row.get(ImportColumn.SUBCATEGORY)));
        }
    }

    private void validateBarcode(SheetRow row, String sku, List<CatalogImportProblem> problems,
                                 Map<ImportColumn, Object> typed,
                                 Map<String, Integer> firstSeen) {
        if (!row.has(ImportColumn.BARCODE)) {
            return;
        }
        String barcode = emptyToNull(row.get(ImportColumn.BARCODE));
        if (barcode == null) {
            typed.put(ImportColumn.BARCODE, null);
            return;
        }
        Integer seenAt = firstSeen.putIfAbsent(barcode, row.rowNumber());
        if (seenAt != null) {
            problems.add(problem(row, "Barcode", Severity.ERROR,
                    "Barcode \"" + barcode + "\" already appears on row " + seenAt + ".",
                    "A barcode identifies one product - two rows cannot share it."));
            return;
        }
        // ALREADY ON A DIFFERENT PRODUCT. Scanning at the packing bench looks
        // the barcode up, so a duplicate makes one of the two unscannable.
        variants.findByBarcode(barcode).ifPresent(existing -> {
            if (sku == null || !barcode.equals(row.get(ImportColumn.BARCODE))
                    || !sku.equalsIgnoreCase(existing.getSku())) {
                problems.add(problem(row, "Barcode", Severity.ERROR,
                        "Barcode \"" + barcode + "\" already belongs to SKU "
                                + existing.getSku() + ".",
                        "Two products cannot share a barcode - the packing scan would be "
                                + "ambiguous."));
            }
        });
        typed.put(ImportColumn.BARCODE, barcode);
    }

    private void validateCategory(SheetRow row, List<CatalogImportProblem> problems,
                                  Map<ImportColumn, Object> typed) {
        if (!row.has(ImportColumn.CATEGORY)) {
            return;
        }
        String name = emptyToNull(row.get(ImportColumn.CATEGORY));
        if (name == null) {
            return;
        }
        Optional<Category> found = categories.findByNameIgnoreCase(name);
        if (found.isEmpty()) {
            problems.add(problem(row, "Category", Severity.ERROR,
                    "There is no category called \"" + name + "\".",
                    "Create the category first, or use one of the existing names exactly."));
            return;
        }
        typed.put(ImportColumn.CATEGORY, found.get().getId());
    }

    private void validateVariant(SheetRow row, List<CatalogImportProblem> problems,
                                 Map<ImportColumn, Object> typed) {
        if (row.has(ImportColumn.VARIANT_VALUE)) {
            String raw = emptyToNull(row.get(ImportColumn.VARIANT_VALUE));
            if (raw != null) {
                Double parsed = parseDouble(raw);
                if (parsed == null || parsed <= 0) {
                    problems.add(problem(row, "Variant Value", Severity.ERROR,
                            "\"" + raw + "\" is not a pack size.",
                            "Use a number - 1 for 1 kg, 500 for 500 g."));
                } else {
                    typed.put(ImportColumn.VARIANT_VALUE, parsed);
                }
            }
        }
        if (row.has(ImportColumn.UNIT)) {
            String raw = emptyToNull(row.get(ImportColumn.UNIT));
            if (raw != null) {
                String canonical = UNITS.get(raw.toLowerCase(Locale.ROOT).replace(".", "").trim());
                if (canonical == null) {
                    problems.add(problem(row, "Unit", Severity.ERROR,
                            "\"" + raw + "\" is not a unit this shop uses.",
                            "Use one of: " + String.join(", ", new LinkedHashSet<>(UNITS.values()))));
                } else {
                    typed.put(ImportColumn.UNIT, canonical);
                }
            }
        }
        if (row.has(ImportColumn.WEIGHT_GRAMS)) {
            BigDecimal weight = money(row, ImportColumn.WEIGHT_GRAMS, "Weight Grams", problems, typed);
            if (weight != null && weight.signum() < 0) {
                problems.add(problem(row, "Weight Grams", Severity.ERROR,
                        "Weight cannot be negative.", null));
                typed.remove(ImportColumn.WEIGHT_GRAMS);
            }
        }
        if (row.has(ImportColumn.GST_RATE)) {
            BigDecimal gst = money(row, ImportColumn.GST_RATE, "GST Rate", problems, typed);
            if (gst != null && (gst.signum() < 0 || gst.compareTo(new BigDecimal("100")) > 0)) {
                problems.add(problem(row, "GST Rate", Severity.ERROR,
                        "A GST rate must be between 0 and 100.", "For 5% write 5, not 0.05."));
                typed.remove(ImportColumn.GST_RATE);
            }
        }
    }

    private void validatePrices(SheetRow row, List<CatalogImportProblem> problems,
                                Map<ImportColumn, Object> typed) {
        BigDecimal mrp = money(row, ImportColumn.MRP, "MRP", problems, typed);
        BigDecimal selling = money(row, ImportColumn.SELLING_PRICE, "Selling Price", problems, typed);
        BigDecimal cost = money(row, ImportColumn.COST_PRICE, "Cost Price", problems, typed);

        for (var pair : List.of(Map.entry("MRP", Optional.ofNullable(mrp)),
                Map.entry("Selling Price", Optional.ofNullable(selling)),
                Map.entry("Cost Price", Optional.ofNullable(cost)))) {
            Optional<BigDecimal> value = pair.getValue();
            if (value.isEmpty()) {
                continue;
            }
            if (value.get().signum() < 0) {
                problems.add(problem(row, pair.getKey(), Severity.ERROR,
                        pair.getKey() + " cannot be negative.", null));
            } else if (value.get().compareTo(MAX_MONEY) > 0) {
                problems.add(problem(row, pair.getKey(), Severity.ERROR,
                        pair.getKey() + " of " + value.get() + " looks like a mistake.",
                        "Check for a misplaced decimal point."));
            }
        }

        // THE ONE THAT COSTS MONEY ON EVERY SALE, not once.
        if (mrp != null && selling != null && selling.compareTo(mrp) > 0) {
            problems.add(problem(row, "Selling Price", Severity.ERROR,
                    "Selling Price " + selling + " is above the MRP " + mrp + ".",
                    "Selling above MRP is not allowed - check which of the two is wrong."));
        }
        if (cost != null && selling != null && selling.compareTo(cost) < 0) {
            problems.add(problem(row, "Selling Price", Severity.WARNING,
                    "Selling Price " + selling + " is below the Cost Price " + cost + ".",
                    "This product would be sold at a loss. Import it only if that is deliberate."));
        }

        // The discount column has nowhere to be stored - it is a cross-check.
        if (row.has(ImportColumn.DISCOUNT)) {
            String raw = emptyToNull(row.get(ImportColumn.DISCOUNT));
            if (raw != null) {
                Double claimed = parseDouble(raw.replace("%", ""));
                if (claimed == null || claimed < 0 || claimed > 100) {
                    problems.add(problem(row, "Discount", Severity.ERROR,
                            "\"" + raw + "\" is not a discount percentage.",
                            "Write 10 for 10%, or leave it empty."));
                } else if (mrp != null && selling != null && mrp.signum() > 0) {
                    double actual = mrp.subtract(selling)
                            .multiply(new BigDecimal("100"))
                            .divide(mrp, 2, java.math.RoundingMode.HALF_UP).doubleValue();
                    if (Math.abs(actual - claimed) > 1.0) {
                        problems.add(problem(row, "Discount", Severity.WARNING,
                                "Discount says " + claimed + "% but MRP and Selling Price work out "
                                        + "to " + String.format(Locale.ROOT, "%.2f", actual) + "%.",
                                "The two prices are what get charged - this column is ignored. "
                                        + "Usually it means one price was edited and the other "
                                        + "was not."));
                    }
                }
            }
        }
    }

    private void validateStock(SheetRow row, List<CatalogImportProblem> problems,
                               Map<ImportColumn, Object> typed) {
        Integer stock = count(row, ImportColumn.STOCK, "Stock", problems, typed);
        Integer low = count(row, ImportColumn.LOW_STOCK_THRESHOLD, "Low Stock Threshold",
                problems, typed);
        if (stock != null && low != null && low > stock) {
            problems.add(problem(row, "Low Stock Threshold", Severity.WARNING,
                    "The low-stock level (" + low + ") is above the stock on hand (" + stock + ").",
                    "This product will show as low on stock immediately."));
        }
    }

    private void validateImages(SheetRow row, List<CatalogImportProblem> problems,
                                Map<ImportColumn, Object> typed) {
        for (ImportColumn column : List.of(ImportColumn.IMAGE_1, ImportColumn.IMAGE_2,
                ImportColumn.IMAGE_3, ImportColumn.IMAGE_4, ImportColumn.IMAGE_5)) {
            if (!row.has(column)) {
                continue;
            }
            String url = emptyToNull(row.get(column));
            if (url == null) {
                typed.put(column, null);
                continue;
            }
            // Reuses the project's existing image-host policy rather than
            // inventing a second, looser one for imports.
            if (!CatalogUrlValidator.isAllowedImageUrl(url)) {
                problems.add(problem(row, column.canonical(), Severity.ERROR,
                        "\"" + trim(url) + "\" is not an image address this shop accepts.",
                        CatalogUrlValidator.IMAGE_MESSAGE));
                continue;
            }
            typed.put(column, url);
        }
    }

    private void validateFlags(SheetRow row, List<CatalogImportProblem> problems,
                               Map<ImportColumn, Object> typed) {
        for (ImportColumn column : List.of(ImportColumn.FEATURED, ImportColumn.BESTSELLER,
                ImportColumn.ACTIVE)) {
            if (!row.has(column)) {
                continue;
            }
            String raw = emptyToNull(row.get(column));
            if (raw == null) {
                continue;
            }
            Boolean parsed = parseBoolean(raw);
            if (parsed == null) {
                problems.add(problem(row, column.canonical(), Severity.ERROR,
                        "\"" + raw + "\" is not a yes or no.",
                        "Use TRUE or FALSE (yes/no and 1/0 also work)."));
            } else {
                typed.put(column, parsed);
            }
        }
    }

    private void validateText(SheetRow row, List<CatalogImportProblem> problems,
                              Map<ImportColumn, Object> typed) {
        if (!row.has(ImportColumn.DESCRIPTION)) {
            return;
        }
        String description = emptyToNull(row.get(ImportColumn.DESCRIPTION));
        if (description != null && description.length() > 1000) {
            problems.add(problem(row, "Description", Severity.ERROR,
                    "Description is longer than 1000 characters.", "Shorten it."));
            return;
        }
        typed.put(ImportColumn.DESCRIPTION, description);
    }

    private void noteDuplicateRow(SheetRow row, List<CatalogImportProblem> problems,
                                  Map<String, Integer> firstSeen) {
        String signature = row.values().toString();
        Integer seenAt = firstSeen.putIfAbsent(signature, row.rowNumber());
        if (seenAt != null) {
            problems.add(problem(row, null, Severity.WARNING,
                    "This row is identical to row " + seenAt + ".",
                    "Importing it twice changes nothing, but it usually means a paste went wrong."));
        }
    }

    // ------------------------------------------------------------- helpers

    private BigDecimal money(SheetRow row, ImportColumn column, String label,
                             List<CatalogImportProblem> problems, Map<ImportColumn, Object> typed) {
        if (!row.has(column)) {
            return null;
        }
        String raw = emptyToNull(row.get(column));
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replace("₹", "").replace(",", "").replace("Rs.", "")
                .replace("Rs", "").trim();
        try {
            BigDecimal value = new BigDecimal(cleaned);
            typed.put(column, value);
            return value;
        } catch (NumberFormatException e) {
            problems.add(problem(row, label, Severity.ERROR,
                    "\"" + raw + "\" is not a number.",
                    "Use digits only, like 56 or 56.50."));
            return null;
        }
    }

    private Integer count(SheetRow row, ImportColumn column, String label,
                          List<CatalogImportProblem> problems, Map<ImportColumn, Object> typed) {
        if (!row.has(column)) {
            return null;
        }
        String raw = emptyToNull(row.get(column));
        if (raw == null) {
            return null;
        }
        try {
            // Accepts "12" and Excel's "12.0", refuses "12.5" - half a packet
            // of atta is not a stock level.
            BigDecimal decimal = new BigDecimal(raw.replace(",", "").trim());
            if (decimal.stripTrailingZeros().scale() > 0) {
                problems.add(problem(row, label, Severity.ERROR,
                        "\"" + raw + "\" is not a whole number.", "Stock is counted in whole units."));
                return null;
            }
            int value = decimal.intValueExact();
            if (value < 0) {
                problems.add(problem(row, label, Severity.ERROR,
                        label + " cannot be negative.", null));
                return null;
            }
            if (value > MAX_STOCK) {
                problems.add(problem(row, label, Severity.ERROR,
                        label + " of " + value + " looks like a mistake.", "Check the number."));
                return null;
            }
            typed.put(column, value);
            return value;
        } catch (ArithmeticException | NumberFormatException e) {
            problems.add(problem(row, label, Severity.ERROR,
                    "\"" + raw + "\" is not a whole number.", "Use digits only, like 24."));
            return null;
        }
    }

    private static Boolean parseBoolean(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "y", "1" -> Boolean.TRUE;
            case "false", "no", "n", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static Double parseDouble(String raw) {
        try {
            return Double.parseDouble(raw.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String emptyToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    private static String trim(String value) {
        return value.length() <= 60 ? value : value.substring(0, 57) + "...";
    }

    private static CatalogImportProblem problem(SheetRow row, String field, Severity severity,
                                                String problem, String suggestion) {
        return new CatalogImportProblem(row.rowNumber(), field, severity, problem, suggestion);
    }
}
