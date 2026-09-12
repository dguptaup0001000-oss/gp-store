package com.gpstore.repository;

import com.gpstore.catalog.shop.Storefront;
import com.gpstore.platform.PlatformProperties;
import com.gpstore.platform.TenantContext;
import com.gpstore.platform.TenantScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SQL this class assembles, asserted as a string.
 *
 * WHY ON THE STRING AND NOT ONLY ON RESULTS. Two of the claims made about
 * these queries cannot be seen in a result set. The first is the text-block
 * trap that once produced "ANDEXISTS" and 500ed every search in production.
 * The second is INERTNESS: the promise that a single-shop deployment runs the
 * query it has always run, which a result-based test cannot distinguish from
 * "the narrowing happened to match everything today".
 */
class ProductBrowseRepositorySearchTest {

    private static ProductBrowseRepository repositoryIn(String mode) {
        return new ProductBrowseRepository(
                new Storefront(new PlatformProperties(mode, "SHOP-1", "", "")));
    }

    @AfterEach
    void clearScope() {
        TenantContext.clear();
    }

    @Test
    void likePatternEscapesWildcardCharacters() {
        assertEquals("%rice%", ProductBrowseRepository.toLikePattern("rice"));
        assertEquals("%100#%%", ProductBrowseRepository.toLikePattern("100%"));
        assertEquals("%a#_b%", ProductBrowseRepository.toLikePattern("a_b"));
        assertEquals("%##tag%", ProductBrowseRepository.toLikePattern("#tag"));
    }

    @Test
    void assembledSqlKeepsSpaceBeforeExists() {
        ProductBrowseRepository repository = repositoryIn("SINGLE_SHOP");
        String trigram = repository.trigramSqlForTest();
        String ilike = repository.ilikeSqlForTest();
        assertTrue(trigram.contains("AND EXISTS"), trigram);
        assertTrue(ilike.contains("AND EXISTS"), ilike);
        assertFalse(trigram.contains("ANDEXISTS"));
        assertFalse(ilike.contains("ANDEXISTS"));
    }

    @Test
    @DisplayName("under SINGLE_SHOP the SQL is untouched, shop in scope or not")
    void singleShopSqlIsInert() {
        ProductBrowseRepository repository = repositoryIn("SINGLE_SHOP");
        String searchBefore = repository.trigramSqlForTest();
        String browseBefore = repository.browseSqlForTest(null, 7L, false);

        // A single-shop deployment always has a shop in scope - TenantResolver
        // hands every request Shop #1 - so this is the ordinary case, not a
        // corner of it.
        TenantContext.set(TenantScope.ofShop(1L));

        assertEquals(searchBefore, repository.trigramSqlForTest(),
                "SINGLE_SHOP MUST RUN THE QUERY IT HAS ALWAYS RUN (§12)");
        assertEquals(browseBefore, repository.browseSqlForTest(null, 7L, false));
        assertFalse(repository.browseSqlForTest(null, 7L, false).contains(Storefront.SHOP_PARAM),
                "no shop parameter is bound under one shop, so none may appear in the SQL");
        assertFalse(repository.browseSqlForTest(null, 7L, false).contains("shop_product_variants"),
                "the shelf table is not consulted under one shop: the catalogue IS the shelf, "
                        + "and a variant priced without ever being listed must keep selling");
    }

    @Test
    @DisplayName("under a marketplace every browse query asks the shelf question")
    void marketplaceSqlIsNarrowed() {
        ProductBrowseRepository repository = repositoryIn("MULTI_SHOP_PRODUCTION");
        TenantContext.set(TenantScope.ofShop(42L));

        String trigram = repository.trigramSqlForTest();
        String ilike = repository.ilikeSqlForTest();
        String browse = repository.browseSqlForTest("Aashirvaad", null, false);

        for (String sql : new String[] {trigram, ilike, browse}) {
            assertTrue(sql.contains("shop_product_variants"),
                    "a browse query that never mentions the shelf cannot be narrowed to it: " + sql);
            assertTrue(sql.contains(":" + Storefront.SHOP_PARAM),
                    "the shop must be BOUND, never interpolated: " + sql);
            assertFalse(sql.contains("ANDEXISTS"), sql);
            assertFalse(sql.contains("AND(SELECT"), sql);
        }

        // Sorting and filtering read the aggregate, so the aggregate itself
        // has to be the shop's prices - not the catalogue's.
        assertTrue(browse.contains("FROM shop_product_variants sp"),
                "\"sort by price\" must sort by THIS shop's price: " + browse);
        // "Best selling" is built from order history, which is shop-owned.
        assertTrue(browse.contains("JOIN orders o ON o.id = oi.order_id"),
                "\"best selling\" must count this shop's own baskets: " + browse);
        assertTrue(browse.contains("WHERE o.shop_id = :" + Storefront.SHOP_PARAM), browse);
    }

    @Test
    @DisplayName("a caller with no shop of its own is not narrowed to a shop")
    void platformScopeIsNotAStorefront() {
        ProductBrowseRepository repository = repositoryIn("MULTI_SHOP_PRODUCTION");
        String unscoped = repository.browseSqlForTest(null, 7L, false);

        TenantContext.set(TenantScope.platform());
        assertEquals(unscoped, repository.browseSqlForTest(null, 7L, false),
                "A PLATFORM ADMIN OWNS NO SHELF. Narrowing to \"their\" shop would narrow to "
                        + "nothing and make the oversight views empty, so the marketplace-wide "
                        + "query is the correct one here - and it is deliberate, not accidental.");
    }

}
