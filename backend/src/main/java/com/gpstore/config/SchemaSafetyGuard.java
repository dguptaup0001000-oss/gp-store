package com.gpstore.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Stops Hibernate from being allowed to rewrite the production schema.
 *
 * Same shape as JwtService's production guard, and for the same reason: a
 * setting that is harmless in development and destructive in production
 * should not be left to a documentation note that nobody reads at 2am.
 *
 * ddl-auto defaults to "update" so local development and CI can spin a schema
 * up from nothing. In production that same default means Hibernate compares
 * its entity model to the live database on every boot and issues DDL to close
 * the gap - so a mistyped column name ships as an ALTER TABLE against real
 * customer data, and a schema change nobody wrote a migration for happens
 * silently and is invisible in the Flyway history. Migrations exist precisely
 * so schema changes are reviewed, ordered and replayable; ddl-auto=update
 * routes around all three.
 *
 * Production responses:
 *
 *   create / create-drop / drop / update  -> REFUSE TO START.
 *       create/drop destroy tables. update silently mutates the live schema.
 *       Neither is acceptable against customer data. The operator sets
 *       DDL_AUTO=validate (or none) and, if startup then fails, writes an
 *       explicit Flyway migration - that disagreement is a finding, not a
 *       reason to keep guessing.
 *
 * validate / none are the two settings this is trying to arrive at and pass
 * silently. Local development and CI are unaffected: the guard is a no-op
 * when app.production is false.
 */
@Component
public class SchemaSafetyGuard {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SchemaSafetyGuard.class);

    private final boolean production;
    private final String ddlAuto;

    public SchemaSafetyGuard(
            @Value("${app.production:false}") boolean production,
            @Value("${spring.jpa.hibernate.ddl-auto:none}") String ddlAuto) {
        this.production = production;
        this.ddlAuto = ddlAuto == null ? "" : ddlAuto.trim().toLowerCase();
    }

    @PostConstruct
    public void checkSchemaManagementIsSafeForProduction() {
        if (!production) {
            return;
        }

        if (isUnsafeInProduction(ddlAuto)) {
            throw new IllegalStateException(
                    "Refusing to start in production with spring.jpa.hibernate.ddl-auto=" + ddlAuto + ". "
                            + "That setting can mutate or destroy the live schema. "
                            + "Set DDL_AUTO=validate in the production environment "
                            + "(Flyway migrations are the only allowed schema changes).");
        }

        if ("validate".equals(ddlAuto) || "none".equals(ddlAuto) || ddlAuto.isBlank()) {
            log.info("Production schema management is safe: ddl-auto={}", ddlAuto.isBlank() ? "(blank)" : ddlAuto);
        }
    }

    private static boolean isUnsafeInProduction(String mode) {
        return "create".equals(mode)
                || "create-drop".equals(mode)
                || "drop".equals(mode)
                || "update".equals(mode);
    }
}
