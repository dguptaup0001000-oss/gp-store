package com.gpstore.catalog.importer;

import jakarta.persistence.*;

/**
 * One complaint about one row.
 *
 * Addressed to a row number and a column name so a shopkeeper can fix it in
 * the spreadsheet they already have open. A problem that only says "invalid
 * price" is a problem nobody can act on.
 */
@Entity
@Table(name = "catalog_import_problems")
public class CatalogImportProblem {

    public enum Severity {
        /** The row will not be imported. */
        ERROR,
        /** The row will be imported, but something looks wrong. */
        WARNING
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    /**
     * The line number in the uploaded file as a person reading it in Excel
     * would count - header is row 1, first product is row 2. Off-by-one here
     * sends somebody to the wrong line of a 10,000 row sheet.
     */
    @Column(name = "row_number", nullable = false)
    private Integer rowNumber;

    @Column(length = 64)
    private String field;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Severity severity;

    @Column(nullable = false, length = 500)
    private String problem;

    @Column(length = 500)
    private String suggestion;

    public CatalogImportProblem() {
    }

    public CatalogImportProblem(Integer rowNumber, String field, Severity severity,
                                String problem, String suggestion) {
        this.rowNumber = rowNumber;
        this.field = field;
        this.severity = severity;
        this.problem = problem;
        this.suggestion = suggestion;
    }

    public Long getId() { return id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public Integer getRowNumber() { return rowNumber; }
    public String getField() { return field; }
    public Severity getSeverity() { return severity; }
    public String getProblem() { return problem; }
    public String getSuggestion() { return suggestion; }
}
