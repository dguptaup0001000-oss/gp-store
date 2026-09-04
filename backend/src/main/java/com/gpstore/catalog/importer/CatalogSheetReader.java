package com.gpstore.catalog.importer;

import com.gpstore.exception.BadRequestException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Turns an uploaded catalogue file into rows, and nothing more.
 *
 * Reading is kept apart from validating on purpose: a parse failure ("this is
 * not a spreadsheet") and a data failure ("row 412 has a negative price") are
 * different problems with different answers, and mixing them produces the
 * unhelpful "import failed" that tells a shopkeeper nothing.
 */
@Component
public class CatalogSheetReader {

    /**
     * A ceiling, not a target. 20,000 rows is a large kirana catalogue several
     * times over; past that the request is almost certainly a mistake, and
     * reading it would spend the server's heap finding out.
     */
    public static final int MAX_ROWS = 20_000;

    public record SheetRow(int rowNumber, Map<ImportColumn, String> values) {
        public String get(ImportColumn column) {
            String value = values.get(column);
            return value == null ? null : value.trim();
        }

        public boolean has(ImportColumn column) {
            return values.containsKey(column);
        }
    }

    public record ImportSheet(
            List<String> rawHeaders,
            /** Only the columns this file actually contains. */
            Set<ImportColumn> presentColumns,
            List<String> unknownHeaders,
            List<SheetRow> rows) {
    }

    public ImportSheet read(String filename, byte[] content) {
        if (content == null || content.length == 0) {
            throw new BadRequestException("That file is empty.");
        }
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xlsx")) {
            return readExcel(content);
        }
        if (lower.endsWith(".csv") || lower.endsWith(".txt")) {
            return readCsv(content);
        }
        if (lower.endsWith(".xls")) {
            throw new BadRequestException(
                    "That is the old Excel format. Open it and choose Save As -> .xlsx, "
                            + "or export it as CSV.");
        }
        throw new BadRequestException(
                "Upload a .csv or .xlsx file. That one is \"" + filename + "\".");
    }

    // ------------------------------------------------------------------ CSV

    private ImportSheet readCsv(byte[] content) {
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setIgnoreSurroundingSpaces(true)
                .setIgnoreEmptyLines(true)
                .build()
                .parse(new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.UTF_8))) {

            List<CSVRecord> records = parser.getRecords();
            if (records.isEmpty()) {
                throw new BadRequestException("That file has no rows at all.");
            }

            List<String> headers = new ArrayList<>();
            records.get(0).forEach(headers::add);

            Map<Integer, ImportColumn> byIndex = mapHeaders(headers);
            List<SheetRow> rows = new ArrayList<>();

            for (int i = 1; i < records.size(); i++) {
                CSVRecord record = records.get(i);
                Map<ImportColumn, String> values = new EnumMap<>(ImportColumn.class);
                byIndex.forEach((index, column) -> {
                    if (index < record.size()) {
                        values.put(column, record.get(index));
                    }
                });
                if (isBlankRow(values)) {
                    continue;
                }
                // +1 so the number matches what a person sees in Excel, where
                // the header is row 1. Sending somebody to the wrong line of a
                // 10,000 row sheet is its own bug.
                rows.add(new SheetRow(i + 1, values));
                requireUnderLimit(rows.size());
            }
            return sheet(headers, byIndex, rows);

        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException(
                    "That CSV could not be read. If it came from Excel, try Save As -> "
                            + "CSV UTF-8. (" + e.getMessage() + ")");
        }
    }

    // ---------------------------------------------------------------- Excel

    private ImportSheet readExcel(byte[] content) {
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) {
                throw new BadRequestException("The first sheet of that file is empty.");
            }
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) {
                throw new BadRequestException("That file has no header row.");
            }

            List<String> headers = new ArrayList<>();
            for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                headers.add(cellText(headerRow.getCell(c)));
            }
            Map<Integer, ImportColumn> byIndex = mapHeaders(headers);

            List<SheetRow> rows = new ArrayList<>();
            for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                Map<ImportColumn, String> values = new EnumMap<>(ImportColumn.class);
                byIndex.forEach((index, column) -> values.put(column, cellText(row.getCell(index))));
                if (isBlankRow(values)) {
                    continue;
                }
                rows.add(new SheetRow(r + 1, values));
                requireUnderLimit(rows.size());
            }
            return sheet(headers, byIndex, rows);

        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException(
                    "That .xlsx could not be read (" + e.getMessage() + ")");
        }
    }

    /**
     * A cell as the person who typed it meant it.
     *
     * NUMBERS ARE THE TRAP. Excel stores 38 as the double 38.0, and
     * getNumericCellValue().toString() yields "38.0" - which then fails price
     * parsing or, worse, imports a weight of 38.0 as text. BigDecimal with the
     * trailing zeros stripped gives back "38", and "1063.50" stays "1063.50".
     */
    private String cellText(Cell cell) {
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType()
                : cell.getCellType();

        return switch (type) {
            case STRING -> cell.getStringCellValue().trim();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().toLocalDate().toString()
                    : BigDecimal.valueOf(cell.getNumericCellValue())
                            .stripTrailingZeros().toPlainString();
            default -> "";
        };
    }

    // ------------------------------------------------------------- shared

    private Map<Integer, ImportColumn> mapHeaders(List<String> headers) {
        Map<Integer, ImportColumn> byIndex = new LinkedHashMap<>();
        Set<ImportColumn> seen = EnumSet.noneOf(ImportColumn.class);

        for (int i = 0; i < headers.size(); i++) {
            ImportColumn column = ImportColumn.match(headers.get(i));
            if (column == null) {
                continue;
            }
            // A SHEET WITH THE SAME COLUMN TWICE is refused rather than
            // silently resolved. "Selling Price" appearing in column D and
            // column M, with different numbers, is a question only the person
            // who made the file can answer.
            if (!seen.add(column)) {
                throw new BadRequestException(
                        "The column \"" + column.canonical() + "\" appears more than once. "
                                + "Delete the duplicate and upload again.");
            }
            byIndex.put(i, column);
        }
        return byIndex;
    }

    private ImportSheet sheet(List<String> headers, Map<Integer, ImportColumn> byIndex,
                              List<SheetRow> rows) {
        List<String> unknown = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i);
            if (!byIndex.containsKey(i) && !ImportColumn.normalise(header).isEmpty()) {
                unknown.add(header.trim());
            }
        }
        Set<ImportColumn> present = byIndex.isEmpty()
                ? EnumSet.noneOf(ImportColumn.class)
                : EnumSet.copyOf(byIndex.values());
        return new ImportSheet(headers, present, unknown, rows);
    }

    private boolean isBlankRow(Map<ImportColumn, String> values) {
        return values.values().stream().allMatch(v -> v == null || v.isBlank());
    }

    private void requireUnderLimit(int size) {
        if (size > MAX_ROWS) {
            throw new BadRequestException(
                    "That file has more than " + MAX_ROWS + " rows. Split it into "
                            + "smaller files and import them one after another.");
        }
    }
}
