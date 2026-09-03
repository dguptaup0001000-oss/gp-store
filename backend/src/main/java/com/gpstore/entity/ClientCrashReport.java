package com.gpstore.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * One crash on one phone, as much of it as is safe to keep.
 *
 * WHY THIS EXISTS AT ALL: the worker APK ships without Firebase on purpose,
 * so its crash handlers previously had nowhere to send anything. See
 * V43__client_crash_reports.sql for the full reasoning.
 *
 * NOT EVIDENCE OF ANYTHING. The message, the stack and the version all come
 * from a phone, so a modified build can claim whatever it likes. What the
 * server does NOT take from the phone is who is reporting - that comes from
 * the authenticated principal - which is the one field that would matter if
 * somebody wanted to write crashes against another account.
 */
@Entity
@Table(name = "client_crash_reports")
public class ClientCrashReport {

    /** Which app sent it. Small and closed on purpose. */
    public enum App { WORKER, CUSTOMER, ADMIN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private App app;

    /**
     * The signed-in customer, when there is one.
     *
     * LAZY and never serialised out of here: a crash report is read by staff,
     * and dragging a whole Customer - password hash, tokens and all - behind
     * it is how those end up somewhere they should not be.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "worker_id")
    private DeliveryPartner worker;

    @Column(name = "app_version", length = 32)
    private String appVersion;

    @Column(name = "build_sha", length = 40)
    private String buildSha;

    @Column(length = 32)
    private String platform;

    @Column(nullable = false)
    private Boolean fatal = true;

    @Column(nullable = false, length = 500)
    private String message;

    /** Already truncated by the service. Never the raw string from the phone. */
    @Column(columnDefinition = "text")
    private String stack;

    @Column(name = "reported_at", nullable = false)
    private LocalDateTime reportedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public App getApp() { return app; }
    public void setApp(App app) { this.app = app; }

    public Customer getCustomer() { return customer; }
    public void setCustomer(Customer customer) { this.customer = customer; }

    public DeliveryPartner getWorker() { return worker; }
    public void setWorker(DeliveryPartner worker) { this.worker = worker; }

    public String getAppVersion() { return appVersion; }
    public void setAppVersion(String appVersion) { this.appVersion = appVersion; }

    public String getBuildSha() { return buildSha; }
    public void setBuildSha(String buildSha) { this.buildSha = buildSha; }

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }

    public Boolean getFatal() { return fatal; }
    public void setFatal(Boolean fatal) { this.fatal = fatal; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getStack() { return stack; }
    public void setStack(String stack) { this.stack = stack; }

    public LocalDateTime getReportedAt() { return reportedAt; }
    public void setReportedAt(LocalDateTime reportedAt) { this.reportedAt = reportedAt; }
}
