package com.gpstore.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Runs Flyway AFTER Hibernate has created the schema, not before it.
 *
 * THE BUG: the application could not start against an empty database at all.
 *
 *     Script V2__add_performance_indexes.sql failed
 *     ERROR: relation "orders" does not exist
 *
 * Spring Boot's default order is flywayInitializer, then entityManagerFactory.
 * That is correct when Flyway owns the schema. It does not here: this
 * application's tables come from Hibernate (ddl-auto, see SchemaSafetyGuard),
 * and every migration from V2 onward is SUPPLEMENTARY - indexes on tables
 * Hibernate makes, columns added to them, a ShedLock table, a couple of
 * sequences. All of them assume the tables already exist, and on a fresh
 * database none of them do.
 *
 * So a new Supabase project, a restore into an empty database, or a staging
 * environment could not be brought up. Production only works because its
 * tables predate the migrations.
 *
 * NOBODY NOTICED BECAUSE CI NEVER RAN THEM. ci.yml sets FLYWAY_ENABLED=false
 * and builds the schema from DDL_AUTO=update, so V2 through V16 have never
 * executed in CI even once.
 *
 * WHY NOT JUST FIX THE MIGRATIONS. Guarding each script on table existence
 * would change its checksum, and Flyway validates checksums of already-applied
 * migrations on every migrate. Editing them would stop the NEXT production
 * deploy from booting - trading a failure nobody has hit yet for one everybody
 * would. The scripts are left exactly as they are.
 *
 * INTERACTION WITH ddl-auto=validate, stated plainly because it is a real
 * constraint on this choice: with Hibernate running first, "validate" would
 * check the schema BEFORE a pending migration adds the column an entity
 * expects, and fail. That is fine today - this application runs "update", and
 * SchemaSafetyGuard already refuses the destructive modes - but if you ever
 * move to "validate", the migrations must become the schema's real source
 * (a proper Flyway baseline) and this class should be deleted in the same
 * change. It is a fix for the schema ownership this application actually has,
 * not an endorsement of it.
 *
 * Disable with app.flyway-after-schema.enabled=false to get Spring Boot's
 * default ordering back.
 */
@Configuration
@ConditionalOnProperty(name = "app.flyway-after-schema.enabled", havingValue = "true", matchIfMissing = true)
public class FlywayAfterSchemaConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayAfterSchemaConfig.class);

    /**
     * Replaces Spring Boot's own initializer with one that does nothing.
     * Defining this bean is what stops Flyway running early - the auto
     * configuration backs off when a FlywayMigrationInitializer is already
     * present.
     */
    @Bean
    public FlywayMigrationInitializer flywayInitializer(Flyway flyway) {
        return new FlywayMigrationInitializer(flyway, f ->
                log.debug("Flyway deferred until after the schema exists; see FlywayAfterSchemaConfig."));
    }

    /**
     * The one that actually migrates.
     *
     * NOT a FlywayMigrationInitializer, and not @DependsOn("entityManagerFactory"),
     * because both of those deadlock. Spring Boot's JPA auto configuration makes
     * entityManagerFactory depend on every FlywayMigrationInitializer bean in the
     * context, so a second initializer pointed the other way is a cycle:
     *
     *     Circular depends-on relationship between
     *     'delayedFlywayInitializer' and 'entityManagerFactory'
     *
     * SmartInitializingSingleton sidesteps it by not participating in the
     * dependency graph at all: it fires once, after every singleton in the
     * context has been created - entityManagerFactory among them, so Hibernate's
     * schema export has already run by the time migrate() is called.
     */
    @Bean
    public SmartInitializingSingleton deferredFlywayMigration(Flyway flyway) {
        return () -> {
            log.info("Running Flyway now that the schema exists (see FlywayAfterSchemaConfig).");
            flyway.migrate();
        };
    }
}
