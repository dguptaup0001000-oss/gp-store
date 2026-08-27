package com.gpstore.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
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
 * NOBODY NOTICED BECAUSE THE DEFAULT CI JOB NEVER RAN THEM. ci.yml's
 * build-and-test job sets FLYWAY_ENABLED=false and builds the schema from
 * DDL_AUTO=update. The sibling schema-migrate job is what actually executes
 * V2 through current against an empty database.
 *
 * WHY NOT JUST FIX THE MIGRATIONS. Guarding each script on table existence
 * would change its checksum, and Flyway validates checksums of already-applied
 * migrations on every migrate. Editing them would stop the NEXT production
 * deploy from booting - trading a failure nobody has hit yet for one everybody
 * would. The scripts are left exactly as they are.
 *
 * IT ONLY APPLIES WHEN HIBERNATE ACTUALLY CREATES THE SCHEMA, and that
 * condition is the correction to an earlier version of this class that was
 * unconditional.
 *
 * The two orderings are each right for a different owner of the schema:
 *
 *   ddl-auto=update  -> Hibernate makes the tables, migrations decorate them.
 *                       Flyway must run AFTER, or V2 fails on a table that
 *                       does not exist yet. That is what this class does.
 *
 *   ddl-auto=validate -> Hibernate makes nothing and only checks. Migrations
 *                       ARE the schema, so Flyway must run BEFORE - which is
 *                       Spring Boot's own default, and this class must stand
 *                       aside entirely.
 *
 * WHAT HAPPENED WHEN IT DID NOT. Production runs validate. A migration added
 * addresses.subzone_locked, Hibernate validated first, and every deploy died
 * on:
 *
 *     Schema-validation: missing column [subzone_locked] in table [addresses]
 *
 * Not a transient failure - a permanent one. Under that ordering, no
 * migration that adds a column can EVER be deployed: Hibernate always
 * validates before the migration that would satisfy it. The earlier version
 * of this file described that risk in a comment and then shipped it anyway,
 * which is worth less than nothing. It is now a condition instead of a
 * paragraph.
 *
 * Disable with app.flyway-after-schema.enabled=false to get Spring Boot's
 * default ordering back.
 */
@Configuration
// BOTH properties must be true, and spring.flyway.enabled is not optional
// here: with Flyway disabled there is no Flyway bean at all, and this class
// asks for one. CI sets FLYWAY_ENABLED=false, so without this condition every
// @SpringBootTest fails to start with
//
//     No qualifying bean of type 'org.flywaydb.core.Flyway' available
//
// which is exactly what happened - the missing CI coverage this class exists
// to talk about is the same gap that let the first version through green
// locally, where Flyway is always on.
@ConditionalOnProperty(
        name = {"app.flyway-after-schema.enabled", "spring.flyway.enabled"},
        havingValue = "true",
        matchIfMissing = true)
@Conditional(FlywayAfterSchemaConfig.SchemaIsOwnedByHibernate.class)
public class FlywayAfterSchemaConfig {

    /**
     * True only when ddl-auto is a mode that CREATES tables.
     *
     * Under validate or none, Hibernate creates nothing, the migrations are
     * the schema, and deferring them past Hibernate's own validation makes
     * every schema change undeployable. Under those modes this whole
     * configuration must be absent so Spring Boot's default ordering - Flyway
     * first, entityManagerFactory depending on it - takes over.
     *
     * Defaults to true when the property is missing, matching
     * application.properties' own default of update.
     */
    static class SchemaIsOwnedByHibernate implements org.springframework.context.annotation.Condition {

        @Override
        public boolean matches(org.springframework.context.annotation.ConditionContext context,
                               org.springframework.core.type.AnnotatedTypeMetadata metadata) {
            String ddlAuto = context.getEnvironment()
                    .getProperty("spring.jpa.hibernate.ddl-auto", "update")
                    .trim()
                    .toLowerCase(java.util.Locale.ROOT);

            return switch (ddlAuto) {
                case "update", "create", "create-drop" -> true;
                // validate, none, or anything unrecognised: do not reorder.
                // Standing aside is the safe default - the worst it costs is
                // the empty-database case this class exists for, and the worst
                // the other way is a deployment that can never succeed.
                default -> false;
            };
        }
    }

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
     *
     * Goes through {@link FlywayMigrationStrategy} when present so failed V27
     * is repaired before migrate on this path too (see FlywayRepairAndMigrateConfig).
     */
    @Bean
    public SmartInitializingSingleton deferredFlywayMigration(
            Flyway flyway,
            ObjectProvider<FlywayMigrationStrategy> migrationStrategy) {
        return () -> {
            log.info("Running Flyway now that the schema exists (see FlywayAfterSchemaConfig).");
            FlywayMigrationStrategy strategy = migrationStrategy.getIfAvailable();
            if (strategy != null) {
                strategy.migrate(flyway);
            } else {
                flyway.migrate();
            }
        };
    }
}
