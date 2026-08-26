package com.gpstore.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "ops_backup_runs")
public class OpsBackupRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "taken_at", nullable = false)
    private Instant takenAt;

    @Column(nullable = false)
    private String filename;

    private Long bytes;

    @Column(length = 64)
    private String sha256;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(length = 1000)
    private String detail;

    public Long getId() { return id; }
    public Instant getTakenAt() { return takenAt; }
    public String getFilename() { return filename; }
    public Long getBytes() { return bytes; }
    public String getSha256() { return sha256; }
    public String getStatus() { return status; }
    public String getDetail() { return detail; }
}
