package com.gpstore.catalog.importer;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** One upload: what it was, what it did, and whether it was ever committed. */
@Entity
@Table(name = "catalog_import_runs")
public class CatalogImportRun {

    public enum Mode {
        /** Create products that are new, update those that already exist. */
        IMPORT,
        /**
         * Refuse to create anything.
         *
         * A price sheet with one typo'd SKU should fail loudly on that row,
         * not quietly invent a product the shop does not sell and will never
         * notice until a customer orders it.
         */
        UPDATE_ONLY
    }

    public enum Status { PREVIEWED, COMMITTED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String filename;

    @Column(name = "admin_email", length = 255)
    private String adminEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Mode mode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "file_sha256", nullable = false, length = 64)
    private String fileSha256;

    @Column(name = "total_rows", nullable = false)
    private Integer totalRows = 0;

    @Column(name = "valid_rows", nullable = false)
    private Integer validRows = 0;

    @Column(name = "warning_rows", nullable = false)
    private Integer warningRows = 0;

    @Column(name = "error_rows", nullable = false)
    private Integer errorRows = 0;

    @Column(name = "created_count", nullable = false)
    private Integer createdCount = 0;

    @Column(name = "updated_count", nullable = false)
    private Integer updatedCount = 0;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "committed_at")
    private LocalDateTime committedAt;

    @PrePersist
    void stamp() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getAdminEmail() { return adminEmail; }
    public void setAdminEmail(String adminEmail) { this.adminEmail = adminEmail; }
    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getFileSha256() { return fileSha256; }
    public void setFileSha256(String fileSha256) { this.fileSha256 = fileSha256; }
    public Integer getTotalRows() { return totalRows; }
    public void setTotalRows(Integer totalRows) { this.totalRows = totalRows; }
    public Integer getValidRows() { return validRows; }
    public void setValidRows(Integer validRows) { this.validRows = validRows; }
    public Integer getWarningRows() { return warningRows; }
    public void setWarningRows(Integer warningRows) { this.warningRows = warningRows; }
    public Integer getErrorRows() { return errorRows; }
    public void setErrorRows(Integer errorRows) { this.errorRows = errorRows; }
    public Integer getCreatedCount() { return createdCount; }
    public void setCreatedCount(Integer createdCount) { this.createdCount = createdCount; }
    public Integer getUpdatedCount() { return updatedCount; }
    public void setUpdatedCount(Integer updatedCount) { this.updatedCount = updatedCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getCommittedAt() { return committedAt; }
    public void setCommittedAt(LocalDateTime committedAt) { this.committedAt = committedAt; }
}
