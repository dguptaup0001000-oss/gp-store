package com.gpstore.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The migration set on the classpath, with no database involved.
 *
 * There is no V1 file, on purpose: {@code spring.flyway.baseline-on-migrate=true}
 * treats an existing schema as already at version 1, so a file named V1 would
 * be skipped. V2 is the first script that actually executes. Adding a V1 now
 * would also break production, whose history already has V2 onward applied.
 *
 * This test is what the default {@code ./mvnw verify} job can check without
 * turning Flyway on: versions are contiguous, start at 2, and a new file
 * cannot open a gap that CI's empty-database job would then fail to name.
 */
class FlywayMigrationInventoryTest {

    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+)__.+\\.sql$");

    @Test
    @DisplayName("versioned migrations start at V2 and have no gaps")
    void versionedMigrationsAreContiguousFromV2() throws IOException {
        List<Integer> versions = classpathVersions();

        assertFalse(versions.isEmpty(), "db/migration must contain at least V2");
        assertEquals(2, versions.getFirst(),
                "The first versioned migration must be V2. A V1 file would be skipped by "
                        + "baseline-on-migrate on existing databases and rejected as out-of-order "
                        + "on production, whose history already starts at V2.");
        for (int i = 0; i < versions.size(); i++) {
            int expected = 2 + i;
            assertEquals(expected, versions.get(i),
                    "Gap or out-of-order file in db/migration: expected V" + expected
                            + " at position " + i + ", found " + versions);
        }
    }

    @Test
    @DisplayName("no V1 file exists on the classpath")
    void thereIsNoV1File() throws IOException {
        assertTrue(classpathVersions().stream().noneMatch(v -> v == 1));
    }

    static List<Integer> classpathVersions() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/V*.sql");
        List<Integer> versions = new ArrayList<>();
        for (Resource resource : resources) {
            String name = resource.getFilename();
            if (name == null) {
                continue;
            }
            Matcher matcher = VERSIONED.matcher(name);
            assertTrue(matcher.matches(),
                    "Migration filename is not V<n>__description.sql: " + name);
            versions.add(Integer.parseInt(matcher.group(1)));
        }
        versions.sort(Integer::compareTo);
        return versions;
    }
}
