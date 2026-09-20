package com.gpstore.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A search predicate must be shaped like the index that exists for it.
 *
 * <h2>Why this is a test and not a code review note</h2>
 *
 * <p>This exact bug has been fixed twice. Both times the SQL looked correct,
 * both times the trigram index existed and was named in a migration, and both
 * times the planner ignored it and sequentially scanned the table.
 *
 * <p>The cause is that Postgres matches an expression index by the EXPRESSION,
 * textually. An index created as
 *
 * <pre>CREATE INDEX ... ON customers USING gin (lower(full_name) gin_trgm_ops)</pre>
 *
 * serves {@code lower(full_name) LIKE :pattern} and does NOT serve
 * {@code lower(COALESCE(full_name, '')) LIKE :pattern}. They are different
 * expressions, so the index does not apply, and nothing anywhere says so - the
 * query still returns the right rows, just by reading every one of them.
 *
 * <p>Measured on 13,455 customers: 5.1 ms with the COALESCE, 0.49 ms without,
 * and the second plan is a Bitmap Index Scan. The gap grows with the table,
 * because one side is O(n) and the other is not.
 *
 * <h2>Why the COALESCE is always removable here</h2>
 *
 * <p>It does nothing. For any pattern, {@code NULL LIKE '%x%'} is NULL, which
 * is not true, so a NULL column is excluded either way. The only pattern where
 * the two differ is the empty one, {@code '%%'}, which matches an empty string
 * but not a NULL - and neither search can produce it: the global search refuses
 * a term under two characters, and the resource filter adds no LIKE predicate
 * at all when the query is blank.
 *
 * <h2>What this test deliberately allows</h2>
 *
 * <p>A COALESCE over TWO OR MORE columns, such as
 * {@code COALESCE(m.display_name, m.legal_name, '')}. That is a real fallback -
 * "the display name, or the legal name when there is no display name" - and not
 * an accident. Rewriting it into an OR would widen what the search matches,
 * which is a product decision rather than a performance fix, so this test does
 * not ask for it.
 */
@DisplayName("Search predicates can use the indexes written for them")
class SearchPredicatesCanUseTheirIndexesTest {

    /**
     * lower(COALESCE(alias.column, '')) - one column, then the empty-string
     * default. The single-column requirement is what separates the inert
     * wrapper this forbids from the deliberate fallback above.
     */
    private static final Pattern INERT_WRAPPER = Pattern.compile(
            "lower\\(COALESCE\\(\\s*[A-Za-z_][A-Za-z0-9_]*\\.[A-Za-z0-9_]+\\s*,\\s*''\\s*\\)\\)");

    @Test
    @DisplayName("no search predicate wraps a single indexed column in COALESCE")
    void noInertCoalesceAroundAnIndexedColumn() throws IOException {
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> tree = Files.walk(Path.of("src/main/java"))) {
            for (Path file : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                Matcher matcher = INERT_WRAPPER.matcher(source);
                while (matcher.find()) {
                    int line = 1 + (int) source.substring(0, matcher.start()).chars()
                            .filter(c -> c == '\n').count();
                    offenders.add(file.getFileName() + ":" + line + "  " + matcher.group());
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "THESE PREDICATES CANNOT USE THEIR OWN TRIGRAM INDEXES.\n\n"
                        + "Postgres matches an expression index textually, so an index on\n"
                        + "lower(col) does not serve lower(COALESCE(col, '')). The query\n"
                        + "still returns the right rows - by scanning the whole table.\n\n"
                        + "The COALESCE does nothing: NULL LIKE '%x%' is already not true.\n"
                        + "Write lower(col) instead.\n\n"
                        + String.join("\n", offenders));
    }

    /**
     * The negative control. Without this, deleting the pattern above or
     * pointing the walk at an empty directory would leave a test that passes
     * because it looked at nothing.
     */
    @Test
    @DisplayName("and the check would actually notice one")
    void theCheckIsNotVacuous() {
        String bad = "WHERE lower(COALESCE(c.full_name, '')) LIKE :pattern";
        String good = "WHERE lower(c.full_name) LIKE :pattern";
        String deliberate = "WHERE lower(COALESCE(m.display_name, m.legal_name, '')) LIKE :pattern";

        assertTrue(INERT_WRAPPER.matcher(bad).find(), "the inert wrapper must be caught");
        assertTrue(!INERT_WRAPPER.matcher(good).find(), "the fixed form must pass");
        assertTrue(!INERT_WRAPPER.matcher(deliberate).find(),
                "a real multi-column fallback is not this test's business");
    }
}
