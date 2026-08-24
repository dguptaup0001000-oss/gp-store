package com.gpstore.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A guard on the deploy failure that had no test and therefore happened.
 *
 * WHAT WENT WRONG. Production runs ddl-auto=validate. FlywayAfterSchemaConfig
 * moved Flyway to AFTER Hibernate so migrations could decorate tables ddl-auto
 * had just created - correct under "update", fatal under "validate", because
 * Hibernate then checks the schema BEFORE the migration that satisfies it has
 * run. Every deploy died on
 *
 *     Schema-validation: missing column [subzone_locked] in table [addresses]
 *
 * and would have died on the next column, and the one after that. The ordering
 * made schema changes permanently undeployable.
 *
 * CI's default verify job still sets FLYWAY_ENABLED=false, so it cannot
 * catch a migrate-then-validate failure. The Condition is still unit-tested
 * here. The sibling {@code schema-migrate} job is what actually runs Flyway
 * against an empty database.
 */
class FlywayOrderingTest {

    private boolean defersFlyway(String ddlAuto) {
        MockEnvironment environment = new MockEnvironment();
        if (ddlAuto != null) {
            environment.setProperty("spring.jpa.hibernate.ddl-auto", ddlAuto);
        }

        var condition = new FlywayAfterSchemaConfig.SchemaIsOwnedByHibernate();
        var context = new org.springframework.context.annotation.ConditionContext() {
            @Override public org.springframework.beans.factory.support.BeanDefinitionRegistry getRegistry() {
                return null;
            }
            @Override public org.springframework.beans.factory.config.ConfigurableListableBeanFactory getBeanFactory() {
                return null;
            }
            @Override public org.springframework.core.env.Environment getEnvironment() {
                return environment;
            }
            @Override public org.springframework.core.io.ResourceLoader getResourceLoader() {
                return null;
            }
            @Override public ClassLoader getClassLoader() {
                return getClass().getClassLoader();
            }
        };
        return condition.matches(context, null);
    }

    @Test
    @DisplayName("under validate, Flyway must run FIRST - this is the production failure")
    void validateMustNotDeferFlyway() {
        assertFalse(defersFlyway("validate"),
                "Hibernate creates nothing under validate, so the migrations ARE the schema and "
                        + "must run before it checks them. Deferring makes every schema change "
                        + "undeployable - which is what took production down.");
    }

    @Test
    @DisplayName("under none, likewise")
    void noneMustNotDeferFlyway() {
        assertFalse(defersFlyway("none"));
    }

    @Test
    @DisplayName("under update, Flyway must still run after - migrations decorate Hibernate's tables")
    void updateStillDefersFlyway() {
        // The original reason this class exists: on an empty database, V2's
        // index on `orders` fails if Flyway goes first, because Hibernate has
        // not made the table yet.
        assertTrue(defersFlyway("update"));
    }

    @Test
    @DisplayName("the create modes make tables too, so they defer as well")
    void createModesDefer() {
        assertTrue(defersFlyway("create"));
        assertTrue(defersFlyway("create-drop"));
    }

    @Test
    @DisplayName("a missing setting follows application.properties' own default of update")
    void missingPropertyDefaultsToUpdate() {
        assertTrue(defersFlyway(null));
    }

    @Test
    @DisplayName("anything unrecognised stands aside rather than reordering")
    void unknownValueIsConservative() {
        // Standing aside costs the empty-database convenience; reordering
        // wrongly costs every future deployment. The asymmetry decides it.
        assertFalse(defersFlyway("something-new"));
        assertFalse(defersFlyway(""));
    }

    @Test
    @DisplayName("the value is read case- and whitespace-insensitively")
    void toleratesUntidyConfiguration() {
        // DDL_AUTO comes from an environment variable a person typed.
        assertTrue(defersFlyway("  UPDATE  "));
        assertFalse(defersFlyway(" Validate "));
    }
}
