package com.gpstore.catalog.importer;

import com.gpstore.catalog.importer.CatalogImportRun.Mode;
import com.gpstore.entity.Category;
import com.gpstore.repository.CategoryRepository;
import com.gpstore.security.WithStaff;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The rows an import refused have to be readable AFTERWARDS.
 *
 * WHY A SECOND ENDPOINT. The problems come back with the preview, so the
 * shopkeeper sees them at the moment they upload. Close the screen and they
 * are gone: the run is in the history list with "3 refused" beside it and no
 * way to find out which three. The CSV next door is for somebody at a desk
 * with the spreadsheet open; this is for the person holding a phone, because
 * the app has no file-saving code and adding a storage permission to every
 * customer install for one admin screen is the worse trade.
 *
 * The list is bounded at the same 500 as the preview. A sheet where every
 * row is wrong must not answer with twenty thousand lines.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
@DisplayName("A past import's refused rows can still be read")
class PastImportProblemsAreReadableTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private CatalogImportService importService;
    @Autowired private CategoryRepository categories;

    private String categoryName;

    @BeforeEach
    void setUp() {
        categoryName = "Past Problems Cat " + System.nanoTime();
        Category category = new Category();
        category.setName(categoryName);
        category.setActive(true);
        categories.save(category);
    }

    /** One good row and one whose selling price is above its MRP. */
    private Long runWithOneBadRow() {
        String sku = "PPR-" + System.nanoTime();
        String body = """
                SKU,Product Name,Category,MRP,Selling Price,Stock
                %s-A,Good Atta,%s,300,270,10
                %s-B,Overpriced Atta,%s,100,150,10
                """.formatted(sku, categoryName, sku, categoryName);

        var preview = importService.preview("sheet.csv",
                body.getBytes(StandardCharsets.UTF_8), Mode.IMPORT, "admin@example.com");

        assertTrue(preview.errorRows() >= 1,
                "the fixture must actually produce a refused row; got " + preview.problems());
        return preview.runId();
    }

    @Test
    @WithStaff
    @DisplayName("the refused rows come back with the row, the column and what to do")
    void problemsComeBackAfterTheScreenIsClosed() throws Exception {
        Long runId = runWithOneBadRow();

        MvcResult result = mockMvc
                .perform(get("/api/admin/catalog/import/" + runId + "/problems"))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus());
        String body = result.getResponse().getContentAsString();

        assertTrue(body.contains("\"row\""), "a problem without its row number is unusable; " + body);
        assertTrue(body.contains("\"severity\":\"ERROR\""), body);
        // Row 3 of the file: the header is row 1, so the second data row is
        // the third line - the number the shopkeeper sees in their sheet.
        assertTrue(body.contains("\"row\":3"),
                "row numbers must match the spreadsheet, header included; " + body);
    }

    @Test
    @WithStaff
    @DisplayName("a run that refused nothing answers with an empty list, not an error")
    void aCleanRunHasNoProblems() throws Exception {
        String sku = "PPR-CLEAN-" + System.nanoTime();
        var preview = importService.preview("clean.csv", """
                SKU,Product Name,Category,MRP,Selling Price,Stock
                %s,Fine Atta,%s,300,270,10
                """.formatted(sku, categoryName).getBytes(StandardCharsets.UTF_8),
                Mode.IMPORT, "admin@example.com");

        MvcResult result = mockMvc
                .perform(get("/api/admin/catalog/import/" + preview.runId() + "/problems"))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus());
        assertEquals("[]", result.getResponse().getContentAsString().trim());
    }

    @Test
    @DisplayName("a customer cannot read what the shop refused to import")
    void theListIsStaffOnly() throws Exception {
        // No @WithStaff. The rows name products, prices and SKUs the shop has
        // not published, and the endpoint sits under the same SYSTEM_ADMIN
        // gate as the rest of /api/admin/catalog/**.
        Long runId = runWithOneBadRow();

        int status = mockMvc
                .perform(get("/api/admin/catalog/import/" + runId + "/problems"))
                .andReturn().getResponse().getStatus();

        assertTrue(status == 401 || status == 403,
                "an anonymous caller must be refused, got " + status);
    }
}
