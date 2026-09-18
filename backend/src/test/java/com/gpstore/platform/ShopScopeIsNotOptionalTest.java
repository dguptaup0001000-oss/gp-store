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
     * Tables whose shop_id is DATA, not a tenant boundary.
     *
     * cart_items is the only one, and filtering it would break the thing it
     * exists for. A basket spans shops by design (§16): the column records
     * which shelf each line came off, so checkout can split the basket into
     * one order per shop. A filter on it would mean a customer adding
     * something from the second kirana watched the first one's items vanish.
     *
     * WHAT PROTECTS IT INSTEAD, and this is the part that has to be true for
     * the exemption to be honest:
     *
     *   the cart belongs to a CUSTOMER, and CartService checks that ownership
     *   on every read and write - the same check that has always stopped one
     *   customer emptying another's basket;
     *
     *   the shop id is stamped by CartItem's own @PrePersist from the tenant
     *   scope, never from a request, so it cannot be chosen by a caller;
     *
     *   and the order it becomes IS shop-owned, filtered and stamped like
     *   every other row - so the moment a basket turns into something a
     *   merchant can see, the boundary is back.
     *
     * All three are asserted by MultiShopCheckoutTest.
     */
    private static final Set<String> SHOP_ID_AS_DATA_NOT_AS_A_BOUNDARY =
            Set.of("audit_logs", "cart_items", "outbox_events");

    /*
     * audit_logs is the third deliberate context column. The stream contains
     * both shop events and platform events, so a null shop is meaningful and
     * marking the entity ShopOwned would make legitimate platform audit rows
     * impossible to insert. AuditLogService stamps the current shop on every
     * shop-scoped write and explicitly predicates both merchant read paths by
     * TenantContext.require(). Platform scope alone receives the full stream.
     * PlatformControlTowerSecurityTest and the audit isolation regression test
     * pin both sides; this exemption is therefore an explicit alternate
     * boundary, not an unowned tenant column.
     */

    /*
     * outbox_events is the second one, and for a different reason worth
     * writing out.
     *
     * ONE WORKER SERVES EVERY SHOP. The drain is a scheduled sweep - filtering
     * it would mean each merchant needing their own worker, or the sweep
     * silently doing one shop's work and nobody else's. So the row stays
     * readable platform-wide and the column says which shop to ACT FOR, not
     * who may read it: OutboxWorker enters TenantScope.ofShop(event.shopId)
     * before dispatching, which is what gives the invoice and the delivery a
     * shop to be stamped with.
     *
     * WHAT PROTECTS IT INSTEAD:
     *
     *   the column is written by the code that CREATES the event, inside the
     *   transaction that created the aggregate, from the scope already on that
     *   thread - never from a request;
     *
     *   the work the event triggers runs inside that shop's scope, so every
     *   row it writes is filtered and stamped like any other;
     *
     *   and V53 verifies that no order event names a shop other than its own
     *   order's, which is the one crossing that would matter - it would send
     *   one shop's rider to another merchant's customer.
     */

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
            "ShopStaffRepository.defaultShopIdFor",

            // DELIBERATELY CROSS-SHOP, AND THAT IS THE QUESTION IT ASKS.
            // "Is anybody else selling this catalogue item?" is what decides
            // whether a merchant may edit the shared description of a variant
            // or only their own price for it (see ShopVariantEditing). Run
            // under the filter it would narrow to the caller and always answer
            // one, which would hand every merchant an edit box over every
            // other merchant's product.
            //
            // It takes no caller input beyond a variant id the caller has
            // already been shown, and it SELECTS count(*) - a number. No row,
            // no price, no shop id and nothing naming another merchant can
            // travel through it, so the cross-shop read leaks nothing.
            "ProductVariantAttributeRepository.countShopsListing");

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

    /**
     * JPQL rooted on an UNFILTERED entity that reaches a shop-owned one
     * through an association, and has been read.
     *
     * THE THIRD BLIND SPOT, and the one that had actually leaked.
     * {@code @Filter} restricts the entity a query is ROOTED on. It does not
     * follow a join. So {@code from OrderItem oi where oi.order.orderDate >=
     * :since} looks scoped, compiles, and aggregates every shop in the
     * marketplace - OrderItem has no shop of its own, and Order's filter is
     * never consulted because Order is not the root.
     *
     * That is not a theory. A probe put 3 units in Shop A and 9 in Shop B;
     * both shops' "top products" reported 12. The refund sum was worse: it
     * reaches Order through two joins, and its result is SUBTRACTED from one
     * shop's revenue, so a brand-new shop's first dashboard reported the
     * marketplace's refunds against its own takings and showed a net loss.
     *
     * A query escapes this list by naming its shop - the test looks for a
     * shopId parameter. Everything else is here with a reason, and a new one
     * fails until somebody writes one.
     */
    private static final Set<String> REVIEWED_JOIN_REACHING_QUERIES = Set.of(
            // KEYED ON A PAYMENT THE CALLER ALREADY HOLDS. Every caller is
            // PaymentService, and each passes payment.getId() from a Payment
            // it loaded through the filtered PaymentRepository - which is
            // where TenantEntityListener's @PostLoad would already have
            // refused a payment belonging to another shop. The id cannot be a
            // foreign one by the time it reaches here, and
            // CrossTenantDataIsolationTest pins that by trying.
            "RefundRepository.committedFor",
            "RefundRepository.settledFor",
            "RefundRepository.highestSequenceFor",
            "RefundRepository.forPayment",

            // CUSTOMER-OWNED, AND DELIBERATELY WIDER THAN ONE SHOP. A
            // customer's own purchase history and their own notifications
            // span every shop they have bought from - that is what a split
            // checkout produces (§16). Both are keyed on the customer id from
            // the verified token, which is the protection; narrowing them to
            // one shop would show a customer half their own history. Same
            // reasoning as CustomerOwnedRead.
            "OrderItemRepository.findByCustomerIdWithProductFetched",
            "NotificationRepository.findByCustomerIdOrderBySentAtDesc",

            // KEYED ON AN ORDER LINE ALREADY PROVED TO BE ON THIS ORDER.
            // ReturnService loads the order through the filtered
            // OrderRepository, then refuses any line whose getOrder() is not
            // that order - so by the time this id arrives it belongs to a
            // shop-owned order in scope. Every return item claiming that line
            // hangs off the same order, so the sum cannot span shops even in
            // principle. Getting it wrong would over-count and REFUSE a
            // legitimate return, never approve a foreign one.
            "OrderReturnRepository.unitsAlreadyClaimedFor",

            // COUNTS THE REPORTER'S OWN CRASHES, for a rate limit. The
            // customer id and worker id both come from the verified
            // credential, never from the body, and a worker belongs to
            // exactly one shop - so the count is single-shop by construction.
            // It returns a number, not a row.
            "ClientCrashReportRepository.countRecentFrom",

            // THE FILTER DOES APPLY, BECAUSE ORDER IS THE SUBQUERY'S ROOT.
            // Notification is not shop-owned - a customer's notifications
            // span every shop they have bought from - so the merchant-facing
            // list narrows itself by existence: it keeps a notification only
            // when the order it is about is one this shop can see, and that
            // EXISTS is ROOTED on Order rather than reached through an
            // association, which is exactly the shape Hibernate's filter does
            // rewrite.
            //
            // THIS ENTRY IS THE RECORD OF A BUG, NOT AN EXCUSE FOR ONE. The
            // first version of this query said "WHERE n.order IS NOT NULL",
            // which never makes Order a root and so was never filtered - it
            // only LOOKED right because TenantEntityListener's @PostLoad threw
            // on the foreign rows and the request surfaced as a 404. The
            // rewrite is what this line is vouching for, and
            // AMerchantReachesOnlyTheirCustomersTest reads the endpoint as one
            // merchant while another merchant's customer holds a notification,
            // so the narrowing is asserted rather than asserted-about.
            "NotificationRepository.findAllForCurrentShop");

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
            if (!enforced.contains(table)
                    && !SETTINGS_SINGLETONS_NOT_YET_SPLIT.contains(table)
                    && !SHOP_ID_AS_DATA_NOT_AS_A_BOUNDARY.contains(table)) {
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
        defaulted.removeIf(SHOP_ID_AS_DATA_NOT_AS_A_BOUNDARY::contains);

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
    @DisplayName("a query that reaches a shop-owned entity through a join has to name its shop")
    void aJoinIsWhereTheFilterStops() {
        Set<String> ownedEntityNames = new TreeSet<>();
        for (Class<?> entity : entityClasses()) {
            if (ShopOwned.class.isAssignableFrom(entity)) {
                ownedEntityNames.add(entity.getSimpleName());
            }
        }
        Set<String> associationsToOwned = associationNamesPointingAtShopOwnedEntities();
        assertFalse(associationsToOwned.isEmpty(),
                "no associations to shop-owned entities found - the scan is wrong, and a guard "
                        + "that finds nothing passes for the wrong reason");

        Set<String> found = new LinkedHashSet<>();
        for (Class<?> repository : repositoryInterfaces()) {
            for (Method method : repository.getDeclaredMethods()) {
                Query query = method.getAnnotation(Query.class);
                if (query == null || query.nativeQuery() || query.value().isBlank()) {
                    continue;   // native queries are covered by their own test
                }
                String jpql = query.value();
                String rootEntity = rootEntityOf(jpql);
                if (rootEntity == null || ownedEntityNames.contains(rootEntity)) {
                    continue;   // the filter applies to the root, so this one is scoped
                }
                if (jpql.contains(":shopId")) {
                    continue;   // it names its shop, which is the way out of this list
                }
                for (String association : associationsToOwned) {
                    if (java.util.regex.Pattern
                            .compile("\\.\\s*" + association + "\\b")
                            .matcher(jpql).find()) {
                        found.add(repository.getSimpleName() + "." + method.getName());
                        break;
                    }
                }
            }
        }

        Set<String> unreviewed = new TreeSet<>(found);
        unreviewed.removeAll(REVIEWED_JOIN_REACHING_QUERIES);

        assertTrue(unreviewed.isEmpty(),
                "these queries are rooted on an entity the shop filter does not cover and reach a "
                        + "shop-owned one through an association. Hibernate does not follow a join: "
                        + "each of these reads every shop in the marketplace. Give it a :shopId "
                        + "parameter read from TenantContext.reportingShopId(), or list it in "
                        + "REVIEWED_JOIN_REACHING_QUERIES with a reason: " + unreviewed);
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
        stale.addAll(REVIEWED_JOIN_REACHING_QUERIES);
        stale.removeAll(everyRepositoryMethod);

        assertTrue(stale.isEmpty(),
                "these entries name methods that no longer exist; an allowlist nobody prunes is "
                        + "how an exception becomes permanent: " + stale);
    }

    // ------------------------------------------------------------- scanning

    /**
     * Every association name that points at a shop-owned entity.
     *
     * Read off the entity classes rather than listed by hand, so an
     * association added to a new entity next month is covered without anyone
     * remembering to come back here.
     */
    private static Set<String> associationNamesPointingAtShopOwnedEntities() {
        Set<String> names = new TreeSet<>();
        for (Class<?> entity : entityClasses()) {
            for (java.lang.reflect.Field field : entity.getDeclaredFields()) {
                Class<?> target = field.getType();
                if (java.util.Collection.class.isAssignableFrom(target)) {
                    java.lang.reflect.Type generic = field.getGenericType();
                    if (generic instanceof java.lang.reflect.ParameterizedType parameterized
                            && parameterized.getActualTypeArguments().length == 1
                            && parameterized.getActualTypeArguments()[0] instanceof Class<?> element) {
                        target = element;
                    }
                }
                if (ShopOwned.class.isAssignableFrom(target)) {
                    names.add(field.getName());
                }
            }
        }
        return names;
    }

    /** The entity a JPQL statement is rooted on - the one the filter covers. */
    private static String rootEntityOf(String jpql) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\bfrom\\s+([A-Za-z_$][\\w$]*)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(jpql);
        return matcher.find() ? matcher.group(1) : null;
    }

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
