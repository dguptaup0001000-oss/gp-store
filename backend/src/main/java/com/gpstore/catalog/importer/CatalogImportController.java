package com.gpstore.catalog.importer;

import com.gpstore.catalog.importer.CatalogImportRun.Mode;
import com.gpstore.exception.BadRequestException;
import com.gpstore.security.CurrentUser;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Bulk catalogue import.
 *
 * Sits under /api/admin/catalog/**, which SecurityConfig already gates on
 * SYSTEM_ADMIN. That is deliberately NOT loosened here: rewriting every price
 * in the shop from one upload is the heaviest thing the catalogue can do, and
 * widening who may do it is a decision for the shop owner, not a side effect
 * of adding the feature.
 */
@RestController
@RequestMapping("/api/admin/catalog/import")
public class CatalogImportController {

    private final CatalogImportService importService;
    private final CatalogImportRunRepository runs;
    private final CatalogImportProblemRepository problems;
    private final CurrentUser currentUser;

    public CatalogImportController(CatalogImportService importService,
                                   CatalogImportRunRepository runs,
                                   CatalogImportProblemRepository problems,
                                   CurrentUser currentUser) {
        this.importService = importService;
        this.runs = runs;
        this.problems = problems;
        this.currentUser = currentUser;
    }

    /**
     * A blank sheet with the right columns.
     *
     * Handed out rather than documented, because the single most common way a
     * bulk import fails is a column named something the importer does not
     * recognise - and the fix for that is to start from a file that is right.
     */
    @GetMapping("/template")
    public ResponseEntity<byte[]> template(
            @RequestParam(defaultValue = "csv") String format) throws IOException {

        boolean excel = "xlsx".equalsIgnoreCase(format);
        String[] headers = java.util.Arrays.stream(ImportColumn.templateOrder())
                .map(ImportColumn::canonical).toArray(String[]::new);

        byte[] body = excel ? excelTemplate(headers) : csvTemplate(headers);
        String filename = "gp-store-catalog-template." + (excel ? "xlsx" : "csv");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(excel
                        ? MediaType.parseMediaType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                        : MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(body);
    }

    /** Check a file and report what it would do. Writes nothing. */
    @PostMapping("/preview")
    public CatalogImportService.ImportSummary preview(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "IMPORT") String mode) throws IOException {

        return importService.preview(originalName(file), file.getBytes(),
                parseMode(mode), currentUser.get().getEmail());
    }

    /**
     * Apply a file that was previewed.
     *
     * The file is uploaded again rather than kept on the server, and the run
     * refuses bytes that differ from what it checked. Storing it instead would
     * mean a second copy of the shop's whole catalogue sitting in the database
     * for every attempt, most of which are abandoned.
     */
    @PostMapping("/{runId}/commit")
    public CatalogImportService.ImportSummary commit(
            @PathVariable Long runId,
            @RequestParam("file") MultipartFile file) throws IOException {

        return importService.commit(runId, originalName(file), file.getBytes(),
                currentUser.get().getEmail());
    }

    /** Who imported what, and how it went. */
    @GetMapping("/history")
    public Page<CatalogImportRun> history(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return runs.findAllByOrderByCreatedAtDesc(PageRequest.of(page, Math.min(size, 100)));
    }

    /**
     * The problems from a PAST run, for reading inside the app.
     *
     * The CSV below is for somebody at a desk with the spreadsheet open. This
     * is for the shopkeeper who imported yesterday, is holding a phone, and
     * wants to know which rows the shop refused - the app has no file-saving
     * code and adding a storage permission to every install for one admin
     * screen would be a worse trade than showing the list.
     *
     * Bounded at the same 500 as the preview: a sheet where every row is
     * wrong must not answer with twenty thousand lines nobody can read.
     */
    @GetMapping("/{runId}/problems")
    public List<CatalogImportService.ProblemView> problemsOf(@PathVariable Long runId) {
        return problems.findByRunIdOrderByRowNumberAsc(runId).stream()
                .limit(500)
                .map(p -> new CatalogImportService.ProblemView(
                        p.getRowNumber(), p.getField(), p.getSeverity().name(),
                        p.getProblem(), p.getSuggestion()))
                .toList();
    }

    /**
     * The failed rows, as a file to open next to the original.
     *
     * CSV rather than JSON because the person fixing this has the spreadsheet
     * open, and "row 412, Selling Price, above MRP" is only useful beside it.
     */
    @GetMapping("/{runId}/problems.csv")
    public ResponseEntity<byte[]> problemReport(@PathVariable Long runId) throws IOException {
        List<CatalogImportProblem> found = problems.findByRunIdOrderByRowNumberAsc(runId);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (CSVPrinter printer = new CSVPrinter(
                new OutputStreamWriter(out, StandardCharsets.UTF_8), CSVFormat.DEFAULT)) {
            printer.printRecord("Row", "Column", "Severity", "Problem", "Suggested correction");
            for (CatalogImportProblem p : found) {
                printer.printRecord(p.getRowNumber(), p.getField(), p.getSeverity(),
                        p.getProblem(), p.getSuggestion());
            }
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"import-" + runId + "-problems.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(out.toByteArray());
    }

    // ------------------------------------------------------------- helpers

    private static Mode parseMode(String raw) {
        try {
            return Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(
                    "Mode must be IMPORT (create and update) or UPDATE_ONLY (never create).");
        }
    }

    private static String originalName(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Choose a file to upload.");
        }
        String name = file.getOriginalFilename();
        return name == null || name.isBlank() ? "upload.csv" : name;
    }

    private static byte[] csvTemplate(String[] headers) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (CSVPrinter printer = new CSVPrinter(
                new OutputStreamWriter(out, StandardCharsets.UTF_8), CSVFormat.DEFAULT)) {
            printer.printRecord((Object[]) headers);
        }
        return out.toByteArray();
    }

    private static byte[] excelTemplate(String[] headers) throws IOException {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Products");
            var header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
