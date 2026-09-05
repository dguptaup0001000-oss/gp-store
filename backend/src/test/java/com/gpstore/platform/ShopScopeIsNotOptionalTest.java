package com.gpstore.platform;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The guard that stops the next change from quietly reopening the boundary.
 *
 * WHY A STRUCTURAL TEST AND NOT MORE DATA TESTS. The data tests prove the rows
 * that exist today are isolated. They say nothing about the table somebody adds
 * next month, or the native query somebody writes next week to make a report
 * faster - and those are how multi-tenant systems actually leak. Nobody
 * deliberately writes a query that crosses shops; they write a perfectly
 * reasonable query and never learn that this one bypasses the filter.
 *
 * So this test asserts the rules themselves:
 *
 *   a table with a shop_id must have an entity that is filtered and stamped -
 *   adding the column is not enough, and adding the column alone is exactly
 *   what a hurried change does;
 *
 *   a shop-owned column may not carry a database default - a default answers
 *   "which shop" without anyone deciding, and answers it wrongly the moment a
 *   second merchant exists;
 *
 *   native queries and bulk updates against shop-owned tables are listed by
 *   name - Hibernate filters neither, so each one is a hand-written predicate
 *   somebody owes, and an unreviewed new one fails here rather than in
 *   production.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("A new table or query cannot silently escape the shop filter")
class ShopScopeIsNotOptionalTest {

    @Autowired private JdbcTemplate jdbc;

    /**
     * Shop-owned tables whose entity is deliberately not filtered yet.
     *
     * EMPTY, and it has to stay that way to mean anything. It held
     * store_operations_settings and delivery_pricing_settings while they were
     * single-row tables loaded by findById(SINGLETON_ID) - a load by primary
     * key that no filter can see. V49 gave each of them one row per shop and
     * the services now find them by shop, so the exemption is gone rather than
     * grandfathered.
     */
    private static final Set<String> SETTINGS_SINGLETONS_NOT_YET_SPLIT = Set.of();

    /**
     * Native queries that touch a shop-owned table and have been read.
     *
     * @Query(nativeQuery = true) goes to the database without Hibernate's
     * filters, so each of these is a place where the shop predicate has to be
     * written by hand. The daily revenue chart is the one that still owes one:
     * under a marketplace it would total every shop's takings into one line.
     * It is listed rather than fixed here because the fix is a signature
     * change on a reporting query, which belongs with the reporting slice -
     * and under SINGLE_SHOP the number it returns today is correct.
     */
    private static final Set<String> REVIEWED_NATIVE_QUERIES = Set.of(
            "OrderRepository.revenueByDayBetween",

            // THE TWO QUERIES THAT MUST NOT BE FILTERED, and the only ones.
            // They answer "which shops may this person work in", which is the
            // question the scope itself is derived from - running them under a
            // shop scope would narrow the answer to the shop being determined,
            // and a merchant with two kiranas would only ever see the one they
            // were already in. Native is how that is made explicit rather than
            // accidental.
            //
            // Neither reads anything a caller sent: the account id comes from
            // the verified token and the rows come from the database. Neither
            // returns a row, only shop ids the credential already permits.
            "ShopStaffRepository.shopIdsFor",
            "ShopStaffRepository.defaultShopIdFor");

    /**
     * Bulk JPQL updates and deletes against shop-owned entities that have been read.
     *
     * Hibernate applies filters to selects, not to "update ... where ..." - so a
     * bulk statement reaches every shop's rows. Each entry is a statement whose
     * where clause has been checked to be safe on its own terms, and a new one
     * fails this test until somebody has done the same.
     */
    private static final Set<String> REVIEWED_BULK_STATEMENTS = Set.of(
            // Now carries "and i.shopId = :shopId", read off the tenant scope
            // rather than off a caller. Listed because a bulk update is still
            // invisible to the filter, so the clause has to survive edits.
            "InventoryRepository.decrementIfAvailable",

            // Deletes ADDRESSES, not orders - Order appears only inside a "not
            // exists" that protects an address an order still points at. The
            // where clause is already keyed to one customer, and a stricter
            // read of orders here would delete MORE, not less.
            "AddressRepository.deleteUnreferencedByCustomerIdBulk");

    @Test
    @DisplayName("every table with a shop_id has an entity that is filtered and stamped")
    void aTenantColumnWithoutEnforcementIsNotAllowed() {
        List<String> tenantTables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.columns
                WHERE table_schema = current_schema() AND column_name = 'shop_id'
                  AND table_name NOT IN ('shops')
                ORDER BY table_name
                """, String.class);

        assertFalse(tenantTables.isEmpty(), "no shop-owned tables found - the query is wrong");

        Set<String> enforced = new TreeSet<>();
        for (Class<?> entity : entityClasses()) {
            if (ShopOwned.class.isAssignableFrom(entity)) {
                enforced.add(tableNameOf(entity));
            }
        }

        List<String> unenforced = new ArrayList<>();
        for (String table : tenantTables) {
            if (!enforced.contains(table) && !SETTINGS_SINGLETONS_NOT_YET_SPLIT.contains(table)) {
                unenforced.add(table);
            }
        }

        assertTrue(unenforced.isEmpty(),
                "these tables carry a shop_id but nothing enforces it - the column looks like "
                        + "isolation and provides none: " + unenforced);
    }

    @Test
    @DisplayName("every shop-owned entity carries the filter and the listener, not one or the other")
    void halfWiredEntitiesAreNotAllowed() {
        List<String> broken = new ArrayList<>();

        for (Class<?> entity : entityClasses()) {
            if (!ShopOwned.class.isAssignableFrom(entity)) {
                continue;
            }
            Filter filter = entity.getAnnotation(Filter.class);
            if (filter == null || !ShopScopeFilter.NAME.equals(filter.name())) {
                broken.add(entity.getSimpleName() + " has no @Filter(\"" + ShopScopeFilter.NAME
                        + "\") - its queries are not scoped");
            }
            EntityListeners listeners = entity.getAnnotation(EntityListeners.class);
            boolean stamped = listeners != null
                    && List.of(listeners.value()).contains(TenantEntityListener.class);
            if (!stamped) {
                broken.add(entity.getSimpleName() + " has no TenantEntityListener - its inserts "
                        + "would write rows belonging to no shop");
            }
        }

        assertTrue(broken.isEmpty(), String.join("; ", broken));
    }

    @Test
    @DisplayName("no shop-owned column may answer 'which shop' with a database default")
    void aDefaultIsNotAnAnswer() {
        List<String> defaulted = jdbc.queryForList("""
                SELECT table_name FROM information_schema.columns
                WHERE table_schema = current_schema() AND column_name = 'shop_id'
                  AND column_default IS NOT NULL
                ORDER BY table_name
                """, String.class);

        defaulted.removeIf(SETTINGS_SINGLETONS_NOT_YET_SPLIT::contains);

        assertTrue(defaulted.isEmpty(),
                "a shop_id default files a forgotten insert under Shop #1 instead of failing, "
                        + "which in a marketplace means one merchant's rows landing in another's "
                        + "books: " + defaulted);
    }

    @Test
    @DisplayName("a new native query against a shop-owned table has to be looked at")
    void nativeQueriesAreNotFilteredSoTheyAreListed() {
        Set<String> tables = shopOwnedTableNames();
        Set<String> found = new LinkedHashSet<>();

        for (Class<?> repository : repositoryInterfaces()) {
            for (Method method : repository.getDeclaredMethods()) {
                Query query = method.getAnnotation(Query.class);
                if (query == null || !query.nativeQuery()) {
                    continue;
                }
                String sql = query.value().toLowerCase(Locale.ROOT);
                if (tables.stream().anyMatch(sql::contains)) {
                    found.add(repository.getSimpleName() + "." + method.getName());
                }
            }
        }

        Set<String> unreviewed = new TreeSet<>(found);
        unreviewed.removeAll(REVIEWED_NATIVE_QUERIES);

        assertTrue(unreviewed.isEmpty(),
                "these native queries read a shop-owned table and Hibernate does not filter them. "
                        + "Give each one an explicit shop predicate, then add it to "
                        + "REVIEWED_NATIVE_QUERIES with a note: " + unreviewed);
    }

    @Test
    @DisplayName("a new bulk update against a shop-owned entity has to be looked at")
    void bulkStatementsAreNotFilteredSoTheyAreListed() {
        Set<String> entityNames = new TreeSet<>();
        for (Class<?> entity : entityClasses()) {
            if (ShopOwned.class.isAssignableFrom(entity)) {
                entityNames.add(entity.getSimpleName().toLowerCase(Locale.ROOT));
            }
        }

        Set<String> found = new LinkedHashSet<>();
        for (Class<?> repository : repositoryInterfaces()) {
            for (Method method : repository.getDeclaredMethods()) {
                if (method.getAnnotation(Modifying.class) == null) {
                    continue;
                }
                Query query = method.getAnnotation(Query.class);
                if (query == null || query.nativeQuery()) {
                    continue;   // native ones are covered by the test above
                }
                String jpql = query.value().toLowerCase(Locale.ROOT);
                if (entityNames.stream().anyMatch(name -> jpql.contains(" " + name + " "))) {
                    found.add(repository.getSimpleName() + "." + method.getName());
                }
            }
        }

        Set<String> unreviewed = new TreeSet<>(found);
        unreviewed.removeAll(REVIEWED_BULK_STATEMENTS);

        assertTrue(unreviewed.isEmpty(),
                "a bulk JPQL update or delete is not filtered by Hibernate, so these reach every "
                        + "shop's rows. Check the where clause, then list it in "
                        + "REVIEWED_BULK_STATEMENTS: " + unreviewed);
    }

    @Test
    @DisplayName("the reviewed lists name real methods, so they cannot rot into permanent excuses")
    void theAllowlistsDoNotOutliveTheirMethods() {
        Set<String> everyRepositoryMethod = new TreeSet<>();
        for (Class<?> repository : repositoryInterfaces()) {
            for (Method method : repository.getDeclaredMethods()) {
                everyRepositoryMethod.add(repository.getSimpleName() + "." + method.getName());
            }
        }

        Set<String> stale = new TreeSet<>();
        stale.addAll(REVIEWED_NATIVE_QUERIES);
        stale.addAll(REVIEWED_BULK_STATEMENTS);
        stale.removeAll(everyRepositoryMethod);

        assertTrue(stale.isEmpty(),
                "these entries name methods that no longer exist; an allowlist nobody prunes is "
                        + "how an exception becomes permanent: " + stale);
    }

    // ------------------------------------------------------------- scanning

    private Set<String> shopOwnedTableNames() {
        Set<String> tables = new TreeSet<>();
        for (Class<?> entity : entityClasses()) {
            if (ShopOwned.class.isAssignableFrom(entity)) {
                tables.add(tableNameOf(entity));
            }
        }
        return tables;
    }

    private static String tableNameOf(Class<?> entity) {
        Table table = entity.getAnnotation(Table.class);
        return table != null && !table.name().isBlank()
                ? table.name().toLowerCase(Locale.ROOT)
                : entity.getSimpleName().toLowerCase(Locale.ROOT);
    }

    private static List<Class<?>> entityClasses() {
        return scan(new AnnotationTypeFilter(Entity.class), false);
    }

    private static List<Class<?>> repositoryInterfaces() {
        return scan(null, true);
    }

    private static List<Class<?>> scan(AnnotationTypeFilter annotation, boolean interfacesOnly) {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(
                            org.springframework.beans.factory.annotation.AnnotatedBeanDefinition bd) {
                        return true;   // interfaces are candidates too
                    }
                };
        if (annotation != null) {
            scanner.addIncludeFilter(annotation);
        } else {
            scanner.addIncludeFilter((reader, factory) ->
                    reader.getClassMetadata().isInterface());
        }

        List<Class<?>> found = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.gpstore")) {
            try {
                Class<?> type = Class.forName(definition.getBeanClassName());
                if (interfacesOnly && !Repository.class.isAssignableFrom(type)) {
                    continue;
                }
                found.add(type);
            } catch (ClassNotFoundException impossible) {
                throw new AssertionError(impossible);
            }
        }
        return found;
    }
}
