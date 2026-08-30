package com.gpstore.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class FlywayOwnedTableResetTest {

    @Test
    @DisplayName("bootstrap drops r2_staging_objects so V30 SQL creates it")
    void dropsR2StagingObjects() {
        assertTrue(FlywayOwnedTableReset.TABLES.contains("r2_staging_objects"));

        List<String> sql = new ArrayList<>();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doAnswer(inv -> {
            sql.add(inv.getArgument(0));
            return null;
        }).when(jdbc).execute(anyString());

        FlywayOwnedTableReset reset = new FlywayOwnedTableReset(jdbc);
        assertEquals(Ordered.HIGHEST_PRECEDENCE, reset.getOrder());
        reset.afterSingletonsInstantiated();

        assertTrue(sql.stream().anyMatch(s ->
                s.toLowerCase().contains("drop table if exists r2_staging_objects")));
    }

    @Test
    @DisplayName("deferred Flyway runs after the reset")
    void deferredFlywayIsLast() {
        assertEquals(Ordered.LOWEST_PRECEDENCE,
                new FlywayAfterSchemaConfig.DeferredFlywayMigration(null, null).getOrder());
    }
}
