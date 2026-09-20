package com.gpstore.catalog.shop;

import com.gpstore.platform.TenantContext;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * Finding the right category out of thousands, from a merchant's point of view.
 *
 * <h2>The problem this solves</h2>
 *
 * <p>The category picker was the full catalogue in id order, which on a kirana
 * meant thirty rows and on a marketplace means several thousand. A phone
 * merchant adding a handset scrolled past Atta, Baby Care, Beverages and
 * Biscuits - somebody else's shop - to reach Mobile Phones. That is not a
 * cosmetic complaint: it is the difference between a merchant listing their
 * stock and giving up.
 *
 * <h2>The order, and why</h2>
 *
 * <p>Three bands, and the reasoning is that a merchant is nearly always adding
 * something like what they already sell:
 *
 * <ol>
 *   <li>CATEGORIES THIS SHOP ALREADY SELLS IN. A phone merchant's next item is
 *       usually another phone or an accessory. This band is almost always the
 *       answer, and it is computed from their own listings rather than from a
 *       business-type label somebody typed at onboarding.</li>
 *   <li>CHILDREN OF THOSE CATEGORIES, so a shop already in Electronics is
 *       offered Mobile Phones and Accessories before anything unrelated.</li>
 *   <li>EVERYTHING ELSE, alphabetically. A business may legitimately expand -
 *       a phone shop starting to sell chargers and then power banks and then
 *       small appliances is a normal trajectory, not an anomaly - so the whole
 *       taxonomy stays reachable. It is just not what a merchant has to wade
 *       through first.</li>
 * </ol>
 *
 * <h2>What this is not</h2>
 *
 * <p>NOT A SECURITY BOUNDARY. Which categories a merchant is shown is a
 * convenience; what they may read and write is decided by shop ownership and
 * the tenant scope, exactly as before. Two shops in the same category share
 * nothing: this class returns category rows, which are central catalogue data
 * and carry no shop's listings, prices or stock.
 */
@Service
public class CategoryFinder {

    /**
     * One category as a picker draws it.
     *
     * @param parentName the parent's name rather than only its id, so a row
     *                   can read "Mobile Phones" with "Electronics" beneath it
     *                   without the client resolving ids itself.
     * @param usedByThisShop whether this shop already has listings here. Drives
     *                       the "Your categories" section and the ordering.
     */
    public record CategoryOption(Long id, String name, Long parentId, String parentName,
                                 String imageUrl, boolean usedByThisShop, long childCount) {}

    private static final int MAX_RESULTS = 60;

    private final NamedParameterJdbcTemplate jdbc;

    public CategoryFinder(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Categories matching what the merchant typed, most relevant first.
     *
     * <p>ONE STATEMENT. The relevance bands are computed in the database as an
     * ordering key rather than by fetching three sets and merging them here,
     * so this costs the same whether the catalogue has thirty categories or
     * thirty thousand.
     *
     * <p>A blank query is not an error - it is the picker opening - and
     * returns the same bands unfiltered, which is how a merchant browsing
     * rather than searching still sees their own categories first.
     */
    @Transactional(readOnly = true)
    public List<CategoryOption> search(String rawQuery, int limit) {
        Long shopId = currentShopIdOrNull();
        String text = rawQuery == null ? "" : rawQuery.trim();
        boolean searching = text.length() >= 2;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("shopId", shopId)
                .addValue("pattern", searching ? "%" + text.toLowerCase(Locale.ROOT) + "%" : null)
                .addValue("limit", Math.max(1, Math.min(limit, MAX_RESULTS)));

        // The shop's own categories, as a set the ordering can test against.
        // LEFT JOIN rather than a correlated subquery per row: one pass.
        String sql = """
                WITH mine AS (
                    SELECT DISTINCT p.category_id
                      FROM shop_product_variants spv
                      JOIN product_variants v ON v.id = spv.product_variant_id
                      JOIN products p ON p.id = v.product_id
                     WHERE spv.shop_id = CAST(:shopId AS bigint)
                       AND p.category_id IS NOT NULL
                )
                SELECT c.id, c.name, c.parent_id, parent.name AS parent_name, c.image_url,
                       (mine.category_id IS NOT NULL) AS used_by_this_shop,
                       (SELECT count(*) FROM categories kid
                         WHERE kid.parent_id = c.id AND kid.active = true) AS child_count,
                       CASE
                         WHEN mine.category_id IS NOT NULL THEN 0
                         WHEN c.parent_id IN (SELECT category_id FROM mine) THEN 1
                         ELSE 2
                       END AS band
                  FROM categories c
                  LEFT JOIN mine ON mine.category_id = c.id
                  LEFT JOIN categories parent ON parent.id = c.parent_id
                 WHERE c.active = true
                   AND (CAST(:pattern AS varchar) IS NULL
                        OR lower(c.name) LIKE CAST(:pattern AS varchar)
                        OR lower(parent.name) LIKE CAST(:pattern AS varchar))
                 ORDER BY band, lower(c.name), c.id
                 LIMIT :limit
                """;

        return jdbc.query(sql, params, (rs, row) -> new CategoryOption(
                rs.getLong("id"), rs.getString("name"),
                (Long) rs.getObject("parent_id"), rs.getString("parent_name"),
                rs.getString("image_url"), rs.getBoolean("used_by_this_shop"),
                rs.getLong("child_count")));
    }

    /**
     * The shop in scope, or null.
     *
     * <p>NULL IS ALLOWED HERE, unlike a catalogue read: categories are central
     * data, so a caller without a shop still gets a sensible universal list -
     * they simply lose the "your categories first" ordering. Refusing would
     * break the picker for a platform-level caller for no safety gain, since
     * nothing shop-owned is returned either way.
     */
    private static Long currentShopIdOrNull() {
        var scope = TenantContext.current();
        return scope == null ? null : scope.shopId();
    }

    /** The direct children of a category, for drilling into a parent. */
    @Transactional(readOnly = true)
    public List<CategoryOption> childrenOf(Long parentId) {
        if (parentId == null) {
            return List.of();
        }
        return jdbc.query("""
                SELECT c.id, c.name, c.parent_id, parent.name AS parent_name, c.image_url,
                       false AS used_by_this_shop,
                       (SELECT count(*) FROM categories kid
                         WHERE kid.parent_id = c.id AND kid.active = true) AS child_count
                  FROM categories c
                  LEFT JOIN categories parent ON parent.id = c.parent_id
                 WHERE c.active = true AND c.parent_id = :parentId
                 ORDER BY lower(c.name), c.id
                """, new MapSqlParameterSource("parentId", parentId),
                (rs, row) -> new CategoryOption(
                        rs.getLong("id"), rs.getString("name"),
                        (Long) rs.getObject("parent_id"), rs.getString("parent_name"),
                        rs.getString("image_url"), false, rs.getLong("child_count")));
    }
}
