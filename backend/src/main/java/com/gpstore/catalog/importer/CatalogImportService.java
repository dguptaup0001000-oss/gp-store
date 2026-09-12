package com.gpstore.catalog.importer;

import com.gpstore.catalog.importer.CatalogImportRun.Mode;
import com.gpstore.catalog.importer.CatalogImportRun.Status;
import com.gpstore.catalog.importer.CatalogImportValidator.Outcome;
import com.gpstore.catalog.importer.CatalogImportValidator.PlannedRow;
import com.gpstore.entity.*;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Bulk catalogue import: preview first, then commit the same file.
 *
 * TWO STEPS, AND THE SECOND CHECKS IT IS THE SAME FILE. The admin uploads,
 * reads "947 valid / 31 warnings / 22 errors", then uploads again to commit;
 * the run stores the SHA-256 of what it previewed and refuses a commit whose
 * bytes differ. Without that, the summary describes one spreadsheet and the
 * import applies another - and nobody would ever notice, because the numbers
 * on screen came from the file they meant to send.
 *
 * EXPLICIT COLUMN SEMANTICS is the other rule this file exists to keep. A
 * sheet containing only SKU and Selling Price updates prices and NOTHING else.
 * It does not blank descriptions, wipe images, or zero the stock of every
 * product in the shop. Only columns actually present in the header are
 * written, which is why the validator hands over a map of present columns
 * rather than a fully populated object.
 */
@Service
public class CatalogImportService {

    private static final Logger log = LoggerFactory.getLogger(CatalogImportService.class);

    private final CatalogSheetReader reader;
    private final CatalogImportValidator validator;
    private final CatalogImportRunRepository runs;
    private final CatalogImportProblemRepository problems;
    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final InventoryRepository inventories;
    private final com.gpstore.catalog.shop.ShopCatalog shopCatalog;
    private final ProductImageRepository images;
    private final CategoryRepository categories;

    public CatalogImportService(CatalogSheetReader reader, CatalogImportValidator validator,
                                CatalogImportRunRepository runs,
                                CatalogImportProblemRepository problems,
                                ProductRepository products, ProductVariantRepository variants,
                                InventoryRepository inventories, ProductImageRepository images,
                                CategoryRepository categories,
                                com.gpstore.catalog.shop.ShopCatalog shopCatalog) {
        this.reader = reader;
        this.validator = validator;
        this.runs = runs;
        this.problems = problems;
        this.products = products;
        this.variants = variants;
        this.inventories = inventories;
        this.images = images;
        this.categories = categories;
        this.shopCatalog = shopCatalog;
    }

    public record ImportSummary(
            Long runId, String filename, String mode, String status,
            int totalRows, int validRows, int warningRows, int errorRows,
            int createdCount, int updatedCount,
            List<ProblemView> problems) {
    }

    public record ProblemView(int row, String field, String severity,
                              String problem, String suggestion) {
    }

    /** Parse and check, write nothing. */
    @Transactional
    public ImportSummary preview(String filename, byte[] content, Mode mode, String adminEmail) {
        Outcome outcome = validator.validate(reader.read(filename, content), mode);

        CatalogImportRun run = new CatalogImportRun();
        run.setFilename(filename);
        run.setAdminEmail(adminEmail);
        run.setMode(mode);
        run.setStatus(Status.PREVIEWED);
        run.setFileSha256(sha256(content));
        run.setTotalRows(outcome.totalRows());
        run.setValidRows(outcome.validRows());
        run.setWarningRows(outcome.warningRows());
        run.setErrorRows(outcome.errorRows());
        CatalogImportRun saved = runs.save(run);

        outcome.problems().forEach(p -> p.setRunId(saved.getId()));
        problems.saveAll(outcome.problems());

        log.info("Catalog import previewed: run={} rows={} valid={} warnings={} errors={}",
                saved.getId(), outcome.totalRows(), outcome.validRows(),
                outcome.warningRows(), outcome.errorRows());
        return summarise(saved, outcome.problems());
    }

    /** Apply a previously previewed file. */
    @Transactional
    public ImportSummary commit(Long runId, String filename, byte[] content, String adminEmail) {
        CatalogImportRun run = runs.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("That import was not found."));

        if (run.getStatus() == Status.COMMITTED) {
            throw new BadRequestException(
                    "This import was already applied on " + run.getCommittedAt()
                            + ". Preview a new file instead of applying this one twice.");
        }
        if (!run.getFileSha256().equals(sha256(content))) {
            throw new BadRequestException(
                    "This is not the file that was previewed. Preview the file you want to "
                            + "import, then apply that one - the counts you saw describe the "
                            + "file you checked, not this one.");
        }

        Outcome outcome = validator.validate(reader.read(filename, content), run.getMode());

        int created = 0;
        int updated = 0;
        for (PlannedRow row : outcome.importable()) {
            if (apply(row, run.getMode())) {
                created++;
            } else {
                updated++;
            }
        }

        run.setStatus(Status.COMMITTED);
        run.setCommittedAt(LocalDateTime.now());
        run.setTotalRows(outcome.totalRows());
        run.setValidRows(outcome.validRows());
        run.setWarningRows(outcome.warningRows());
        run.setErrorRows(outcome.errorRows());
        run.setCreatedCount(created);
        run.setUpdatedCount(updated);
        runs.save(run);

        // Re-record problems: the second validation is the authoritative one,
        // because it ran against the database as it is now.
        problems.deleteByRunId(run.getId());
        outcome.problems().forEach(p -> p.setRunId(run.getId()));
        problems.saveAll(outcome.problems());

        log.info("Catalog import committed: run={} created={} updated={} skipped={}",
                run.getId(), created, updated, outcome.errorRows());
        return summarise(run, outcome.problems());
    }

    /**
     * Writes one row.
     *
     * @return true when a new product was created.
     */
    private boolean apply(PlannedRow row, Mode mode) {
        Map<ImportColumn, Object> values = row.values();

        ProductVariant variant = variants.findBySku(row.sku()).orElse(null);
        boolean creating = variant == null;

        if (creating) {
            if (mode == Mode.UPDATE_ONLY) {
                return false;   // the validator already refused these
            }
            Product product = new Product();
            product.setName((String) values.get(ImportColumn.PRODUCT_NAME));
            product.setActive(true);
            products.save(product);

            variant = new ProductVariant();
            variant.setProduct(product);
            variant.setSku(row.sku());
            variant.setActive(true);
            variant.setAvailable(true);
        }

        Product product = variant.getProduct();

        // ---- product-level, only what the sheet actually contained --------
        if (values.containsKey(ImportColumn.PRODUCT_NAME)) {
            product.setName((String) values.get(ImportColumn.PRODUCT_NAME));
        }
        if (values.containsKey(ImportColumn.BRAND)) {
            product.setBrand((String) values.get(ImportColumn.BRAND));
        }
        if (values.containsKey(ImportColumn.SUBCATEGORY)) {
            product.setSubcategory((String) values.get(ImportColumn.SUBCATEGORY));
        }
        if (values.containsKey(ImportColumn.DESCRIPTION)) {
            product.setDescription((String) values.get(ImportColumn.DESCRIPTION));
        }
        if (values.containsKey(ImportColumn.CATEGORY)) {
            categories.findById((Long) values.get(ImportColumn.CATEGORY))
                    .ifPresent(product::setCategory);
        }
        if (values.containsKey(ImportColumn.FEATURED)) {
            product.setFeatured((Boolean) values.get(ImportColumn.FEATURED));
        }
        if (values.containsKey(ImportColumn.BESTSELLER)) {
            product.setBestseller((Boolean) values.get(ImportColumn.BESTSELLER));
        }
        if (values.containsKey(ImportColumn.ACTIVE)) {
            product.setActive((Boolean) values.get(ImportColumn.ACTIVE));
        }
        products.save(product);

        // ---- variant-level ------------------------------------------------
        if (values.containsKey(ImportColumn.BARCODE)) {
            variant.setBarcode((String) values.get(ImportColumn.BARCODE));
        }
        if (values.containsKey(ImportColumn.VARIANT_VALUE)) {
            variant.setQuantity((Double) values.get(ImportColumn.VARIANT_VALUE));
        }
        if (values.containsKey(ImportColumn.UNIT)) {
            variant.setUnit((String) values.get(ImportColumn.UNIT));
        }
        if (values.containsKey(ImportColumn.MRP)) {
            variant.setMrp((BigDecimal) values.get(ImportColumn.MRP));
        }
        if (values.containsKey(ImportColumn.SELLING_PRICE)) {
            variant.setSellingPrice((BigDecimal) values.get(ImportColumn.SELLING_PRICE));
        }
        if (values.containsKey(ImportColumn.COST_PRICE)) {
            variant.setCostPrice((BigDecimal) values.get(ImportColumn.COST_PRICE));
        }
        if (values.containsKey(ImportColumn.WEIGHT_GRAMS)) {
            variant.setWeightGrams((BigDecimal) values.get(ImportColumn.WEIGHT_GRAMS));
        }
        if (values.containsKey(ImportColumn.GST_RATE)) {
            variant.setGstRateOverride((BigDecimal) values.get(ImportColumn.GST_RATE));
        }
        ProductVariant savedVariant = variants.save(variant);

        // An imported row is a price THIS shop is setting, so the shop's own
        // listing moves with the catalogue row. Without this an import would
        // update the catalogue default and leave the shelf showing the old
        // price - the sheet applied to the wrong table.
        shopCatalog.list(savedVariant);

        // ---- stock --------------------------------------------------------
        if (values.containsKey(ImportColumn.STOCK)
                || values.containsKey(ImportColumn.LOW_STOCK_THRESHOLD)) {
            Inventory inventory = inventories.findByProductVariantId(savedVariant.getId())
                    .orElseGet(() -> {
                        Inventory fresh = new Inventory();
                        fresh.setProductVariant(savedVariant);
                        fresh.setStock(0);
                        return fresh;
                    });
            if (values.containsKey(ImportColumn.STOCK)) {
                inventory.setStock((Integer) values.get(ImportColumn.STOCK));
            }
            if (values.containsKey(ImportColumn.LOW_STOCK_THRESHOLD)) {
                inventory.setMinimumStock((Integer) values.get(ImportColumn.LOW_STOCK_THRESHOLD));
            }
            inventories.save(inventory);
        }

        applyImages(product, values);
        return creating;
    }

    /**
     * Images, slot by slot.
     *
     * A sheet with only "Image 1" replaces the first picture and LEAVES THE
     * REST ALONE. Treating the image columns as the whole gallery would mean
     * a price-and-photo sheet silently deleted every second and third product
     * shot in the shop.
     */
    private void applyImages(Product product, Map<ImportColumn, Object> values) {
        List<ImportColumn> slots = List.of(ImportColumn.IMAGE_1, ImportColumn.IMAGE_2,
                ImportColumn.IMAGE_3, ImportColumn.IMAGE_4, ImportColumn.IMAGE_5);

        for (int slot = 0; slot < slots.size(); slot++) {
            ImportColumn column = slots.get(slot);
            if (!values.containsKey(column)) {
                continue;
            }
            String url = (String) values.get(column);
            final int sortOrder = slot;

            ProductImage existing = images.findByProductIdOrderBySortOrderAsc(product.getId())
                    .stream()
                    .filter(image -> image.getSortOrder() != null && image.getSortOrder() == sortOrder)
                    .findFirst()
                    .orElse(null);

            if (url == null) {
                // An empty cell in a column that IS present means "remove this
                // picture" - the admin deliberately cleared it.
                if (existing != null) {
                    images.delete(existing);
                }
                continue;
            }
            if (existing == null) {
                existing = new ProductImage();
                existing.setProduct(product);
                existing.setSortOrder(sortOrder);
            }
            existing.setImageUrl(url);
            images.save(existing);
        }
    }

    private ImportSummary summarise(CatalogImportRun run, List<CatalogImportProblem> found) {
        List<ProblemView> views = found.stream()
                // Bounded: a sheet where every row is wrong should not answer
                // with 20,000 lines the admin cannot read anyway.
                .limit(500)
                .map(p -> new ProblemView(p.getRowNumber(), p.getField(),
                        p.getSeverity().name(), p.getProblem(), p.getSuggestion()))
                .toList();

        return new ImportSummary(run.getId(), run.getFilename(), run.getMode().name(),
                run.getStatus().name(), run.getTotalRows(), run.getValidRows(),
                run.getWarningRows(), run.getErrorRows(), run.getCreatedCount(),
                run.getUpdatedCount(), views);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
