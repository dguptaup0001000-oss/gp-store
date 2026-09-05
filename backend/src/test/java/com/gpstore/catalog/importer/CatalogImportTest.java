package com.gpstore.catalog.importer;

import com.gpstore.catalog.importer.CatalogImportRun.Mode;
import com.gpstore.entity.*;
import com.gpstore.exception.BadRequestException;
import com.gpstore.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bulk catalogue import.
 *
 * The two things this suite is really about:
 *
 * 1. A PARTIAL SHEET MUST NOT ERASE WHAT IT DOES NOT MENTION. A file with
 *    SKU and Selling Price updates prices. If it also blanked descriptions,
 *    wiped photos and zeroed stock, one upload would take the shop's whole
 *    catalogue with it and the admin would see "947 updated".
 *
 * 2. CORRUPT ROWS MUST NOT IMPORT SILENTLY. A price typed as 5600 instead of
 *    56.00 is not a warning to scroll past - the row is refused, and the
 *    message says which row, which column, and what to do.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("Bulk catalogue import")
class CatalogImportTest {

    @Autowired private CatalogImportService importService;
    @Autowired private ProductRepository products;
    @Autowired private ProductVariantRepository variants;
    @Autowired private InventoryRepository inventories;
    @Autowired private ProductImageRepository images;
    @Autowired private CategoryRepository categories;

    private String categoryName;
    private String sku;

    @BeforeEach
    void setUp() {
        categoryName = "Import Test Cat " + System.nanoTime();
        Category category = new Category();
        category.setName(categoryName);
        category.setActive(true);
        categories.save(category);
        sku = "IMP-" + System.nanoTime();
    }

    private static byte[] csv(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private CatalogImportService.ImportSummary run(String body, Mode mode) {
        byte[] bytes = csv(body);
        var preview = importService.preview("sheet.csv", bytes, mode, "admin@example.com");
        return importService.commit(preview.runId(), "sheet.csv", bytes, "admin@example.com");
    }

    /** A product with everything filled in, so we can watch what survives. */
    private ProductVariant seedFullProduct() {
        run("""
            SKU,Product Name,Brand,Category,Description,MRP,Selling Price,Stock,Image 1
            %s,Aashirvaad Atta 5 kg,Aashirvaad,%s,Chakki fresh atta,300,270,40,https://res.cloudinary.com/demo/image/upload/a.jpg
            """.formatted(sku, categoryName), Mode.IMPORT);
        return variants.findBySku(sku).orElseThrow();
    }

    @Test
    @DisplayName("a sheet of only SKU and price changes the price and nothing else")
    void aPartialSheetDoesNotEraseAbsentColumns() {
        ProductVariant before = seedFullProduct();
        Long productId = before.getProduct().getId();

        run("""
            SKU,Selling Price
            %s,255
            """.formatted(sku), Mode.UPDATE_ONLY);

        ProductVariant after = variants.findBySku(sku).orElseThrow();
        Product product = products.findById(productId).orElseThrow();

        assertEquals(0, new BigDecimal("255").compareTo(after.getSellingPrice()),
                "the price the sheet did carry was not applied");

        // EVERYTHING THE SHEET DID NOT MENTION, still there.
        assertEquals("Aashirvaad Atta 5 kg", product.getName(), "the name was erased");
        assertEquals("Aashirvaad", product.getBrand(), "the brand was erased");
        assertEquals("Chakki fresh atta", product.getDescription(), "the description was erased");
        assertNotNull(product.getCategory(), "the category was erased");
        assertEquals(0, new BigDecimal("300").compareTo(after.getMrp()), "the MRP was erased");
        assertEquals(40, inventories.findByProductVariantId(after.getId()).orElseThrow().getStock(),
                "the stock was zeroed by a sheet that never mentioned stock");
        assertFalse(images.findByProductIdOrderBySortOrderAsc(productId).isEmpty(),
                "the product photo was deleted by a price update");
    }

    @Test
    @DisplayName("a new product arrives with its variant and its stock")
    void importCreatesProductVariantAndInventory() {
        var summary = run("""
            SKU,Product Name,Brand,Category,Variant Value,Unit,MRP,Selling Price,Cost Price,Stock
            %s,Tata Salt 1 kg,Tata,%s,1,kg,28,25,20,150
            """.formatted(sku, categoryName), Mode.IMPORT);

        assertEquals(1, summary.createdCount());
        assertEquals(0, summary.errorRows());

        ProductVariant variant = variants.findBySku(sku).orElseThrow();
        // Loaded by id rather than through the lazy proxy: this test runs
        // outside a session, and product.getName() on the proxy would fail for
        // a reason that has nothing to do with importing.
        Product created = products.findById(variant.getProduct().getId()).orElseThrow();
        assertEquals("Tata Salt 1 kg", created.getName());
        assertEquals("kg", variant.getUnit());
        assertEquals(1.0, variant.getQuantity());
        assertEquals(0, new BigDecimal("20").compareTo(variant.getCostPrice()),
                "cost price must import - free delivery is worked out from margin");
        assertEquals(150, inventories.findByProductVariantId(variant.getId()).orElseThrow().getStock());
    }

    @Test
    @DisplayName("selling above MRP is refused, and the row is not imported")
    void sellingAboveMrpIsRefused() {
        var summary = run("""
            SKU,Product Name,Category,MRP,Selling Price
            %s,Overpriced Dal,%s,100,140
            """.formatted(sku, categoryName), Mode.IMPORT);

        assertEquals(1, summary.errorRows());
        assertEquals(0, summary.createdCount());
        assertTrue(variants.findBySku(sku).isEmpty(), "a refused row was imported anyway");

        var problem = summary.problems().stream()
                .filter(p -> "Selling Price".equals(p.field())).findFirst().orElseThrow();
        assertEquals(2, problem.row(), "the row number must match what Excel shows");
        assertTrue(problem.problem().contains("above the MRP"), problem.problem());
    }

    @Test
    @DisplayName("a negative price and a negative stock are both refused")
    void negativeValuesAreRefused() {
        var summary = run("""
            SKU,Product Name,Category,Selling Price,Stock
            %s,Bad Row,%s,-5,-3
            """.formatted(sku, categoryName), Mode.IMPORT);

        assertEquals(1, summary.errorRows());
        assertTrue(summary.problems().stream().anyMatch(p -> "Selling Price".equals(p.field())));
        assertTrue(summary.problems().stream().anyMatch(p -> "Stock".equals(p.field())));
    }

    @Test
    @DisplayName("the same SKU twice in one file is refused, naming the first row")
    void duplicateSkuInFileIsRefused() {
        var summary = run("""
            SKU,Product Name,Category,Selling Price
            %s,First,%s,10
            %s,Second,%s,20
            """.formatted(sku, categoryName, sku, categoryName), Mode.IMPORT);

        assertEquals(1, summary.errorRows(), "one of the two rows must be refused");
        var problem = summary.problems().stream()
                .filter(p -> p.problem().contains("already appears on row")).findFirst().orElseThrow();
        assertTrue(problem.problem().contains("row 2"), problem.problem());
    }

    @Test
    @DisplayName("a category that does not exist is refused rather than invented")
    void unknownCategoryIsRefused() {
        var summary = run("""
            SKU,Product Name,Category,Selling Price
            %s,Orphan,No Such Category,10
            """.formatted(sku), Mode.IMPORT);

        assertEquals(1, summary.errorRows());
        assertTrue(summary.problems().stream()
                .anyMatch(p -> "Category".equals(p.field())
                        && p.problem().contains("No Such Category")));
    }

    @Test
    @DisplayName("an unknown unit is refused, and the message lists the real ones")
    void unknownUnitIsRefused() {
        var summary = run("""
            SKU,Product Name,Category,Unit,Selling Price
            %s,Odd Unit,%s,furlong,10
            """.formatted(sku, categoryName), Mode.IMPORT);

        var problem = summary.problems().stream()
                .filter(p -> "Unit".equals(p.field())).findFirst().orElseThrow();
        assertTrue(problem.suggestion().contains("kg"), problem.suggestion());
    }

    @Test
    @DisplayName("kgs, KG and kilogram all mean kg")
    void unitAliasesAreAccepted() {
        run("""
            SKU,Product Name,Category,Unit,Selling Price
            %s,Alias Unit,%s,KGS,10
            """.formatted(sku, categoryName), Mode.IMPORT);

        assertEquals("kg", variants.findBySku(sku).orElseThrow().getUnit());
    }

    @Test
    @DisplayName("a product name containing a comma survives the CSV")
    void commasInsideQuotedValuesSurvive() {
        run("""
            SKU,Product Name,Category,Selling Price
            %s,"Haldiram's Bhujia, 200 g",%s,55
            """.formatted(sku, categoryName), Mode.IMPORT);

        ProductVariant variant = variants.findBySku(sku).orElseThrow();
        Product created = products.findById(variant.getProduct().getId()).orElseThrow();
        assertEquals("Haldiram's Bhujia, 200 g", created.getName());
    }

    @Test
    @DisplayName("update-only refuses to invent a product for a typo'd SKU")
    void updateOnlyNeverCreates() {
        var summary = run("""
            SKU,Selling Price
            NOSUCHSKU-%d,25
            """.formatted(System.nanoTime()), Mode.UPDATE_ONLY);

        assertEquals(1, summary.errorRows());
        assertEquals(0, summary.createdCount());
    }

    @Test
    @DisplayName("a preview writes nothing at all")
    void previewIsReadOnly() {
        byte[] bytes = csv("""
            SKU,Product Name,Category,Selling Price
            %s,Preview Only,%s,10
            """.formatted(sku, categoryName));

        importService.preview("sheet.csv", bytes, Mode.IMPORT, "admin@example.com");

        assertTrue(variants.findBySku(sku).isEmpty(),
                "previewing a file created products - the admin has not agreed to anything yet");
    }

    @Test
    @DisplayName("committing a different file than the one previewed is refused")
    void commitMustBeTheFileThatWasPreviewed() {
        byte[] previewed = csv("""
            SKU,Product Name,Category,Selling Price
            %s,Honest,%s,10
            """.formatted(sku, categoryName));
        byte[] swapped = csv("""
            SKU,Product Name,Category,Selling Price
            %s,Swapped,%s,1
            """.formatted(sku, categoryName));

        var preview = importService.preview("sheet.csv", previewed, Mode.IMPORT, "admin@example.com");

        // THE DANGEROUS ONE. The counts on screen describe the previewed file;
        // applying a different one would import numbers nobody checked.
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> importService.commit(preview.runId(), "sheet.csv", swapped, "admin@example.com"));
        assertTrue(ex.getMessage().contains("not the file that was previewed"), ex.getMessage());
        assertTrue(variants.findBySku(sku).isEmpty());
    }

    @Test
    @DisplayName("the same import cannot be applied twice")
    void commitIsNotRepeatable() {
        byte[] bytes = csv("""
            SKU,Product Name,Category,Selling Price
            %s,Once,%s,10
            """.formatted(sku, categoryName));

        var preview = importService.preview("sheet.csv", bytes, Mode.IMPORT, "admin@example.com");
        importService.commit(preview.runId(), "sheet.csv", bytes, "admin@example.com");

        assertThrows(BadRequestException.class,
                () -> importService.commit(preview.runId(), "sheet.csv", bytes, "admin@example.com"));
    }

    @Test
    @DisplayName("a discount that disagrees with the prices warns but still imports")
    void discountMismatchIsAWarningNotAnError() {
        var summary = run("""
            SKU,Product Name,Category,MRP,Selling Price,Discount
            %s,Mismatched,%s,100,90,50
            """.formatted(sku, categoryName), Mode.IMPORT);

        assertEquals(0, summary.errorRows(), "the two prices are valid on their own");
        assertEquals(1, summary.warningRows());
        assertTrue(variants.findBySku(sku).isPresent(), "the row should still import");
        assertTrue(summary.problems().stream()
                .anyMatch(p -> "Discount".equals(p.field()) && "WARNING".equals(p.severity())));
    }

    @Test
    @DisplayName("a column claiming to set New is refused with a reason")
    void unsupportedColumnIsExplained() {
        var summary = run("""
            SKU,Product Name,Category,Selling Price,New
            %s,Newish,%s,10,TRUE
            """.formatted(sku, categoryName), Mode.IMPORT);

        var problem = summary.problems().stream()
                .filter(p -> "New".equalsIgnoreCase(p.field())).findFirst().orElseThrow();
        assertEquals("ERROR", problem.severity());
        assertTrue(problem.problem().contains("worked out from when a product was added"),
                problem.problem());
    }

    @Test
    @DisplayName("an unrecognised column is a warning, and the rest of the row imports")
    void unknownColumnIsOnlyAWarning() {
        var summary = run("""
            SKU,Product Name,Category,Selling Price,Supplier Notes
            %s,Fine,%s,10,call Ramesh
            """.formatted(sku, categoryName), Mode.IMPORT);

        assertEquals(1, summary.createdCount());
        assertTrue(summary.problems().stream()
                .anyMatch(p -> "WARNING".equals(p.severity())
                        && p.problem().contains("not recognised")));
    }

    @Test
    @DisplayName("history records what was imported, by whom")
    void historyIsRecorded() {
        var summary = run("""
            SKU,Product Name,Category,Selling Price
            %s,Historic,%s,10
            """.formatted(sku, categoryName), Mode.IMPORT);

        assertEquals("COMMITTED", summary.status());
        assertEquals(1, summary.totalRows());
        assertEquals(1, summary.createdCount());
        assertEquals("sheet.csv", summary.filename());
    }
}
