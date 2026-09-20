package com.gpstore.catalog.shop;

import com.gpstore.platform.TenantContext;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A merchant's own shelf, read the way their screens actually need it.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code GET /api/shop/listings} returns prices and ids and nothing else -
 * no product name, no image, no commerce mode. A screen listing "everything
 * you sell as Visit to Buy" cannot be built on it without fetching each
 * product separately, which is the N+1 this project has spent a lot of effort
 * removing. So this reads the listing and the catalogue row it points at in
 * ONE statement, and filters by commerce mode in the database.
 *
 * <h2>Scope</h2>
 *
 * <p>The shop is never a parameter. It comes from {@link TenantContext}, which
 * the credential resolved, and is written into the statement as an explicit
 * predicate. This is hand-written SQL so the Hibernate filter does not apply
 * automatically - the predicate is the boundary, it is right here, and
 * ShopScopeIsNotOptionalTest lists this class with that reasoning.
 */
@Service
public class ShopCatalogueBrowse {

    /** A merchant reading their own shelf, one page at a time. */
    public record CatalogueItem(Long productVariantId, Long productId, String name,
                                String brand, String imageUrl, Long categoryId,
                                String categoryName, String variantLabel,
                                BigDecimal sellingPrice, BigDecimal mrp,
                                BigDecimal priceMax, String priceMode,
                                String commerceMode, String commerceLabel,
                                String offlineAvailability, Integer serviceDurationMinutes,
                                Boolean available, Boolean active, Integer stock) {}

    public record CataloguePage(List<CatalogueItem> content, int page, int size,
                                long totalElements, int totalPages) {}

    private static final int MAX_PAGE_SIZE = 100;

    private final NamedParameterJdbcTemplate jdbc;

    public ShopCatalogueBrowse(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * What this shop sells in these commerce modes.
     *
     * @param modes which modes to include. Empty means all three, which is
     *              what a general catalogue screen wants; the Visit-to-Buy and
     *              Services screens each pass their own.
     * @param query optional free text over product name, brand and category.
     */
    @Transactional(readOnly = true)
    public CataloguePage page(Collection<CommerceMode> modes, String query,
                              int requestedPage, int requestedSize) {
        Long shopId = currentShopId();
        int page = Math.max(0, requestedPage);
        int size = Math.max(1, Math.min(requestedSize, MAX_PAGE_SIZE));

        List<String> modeNames = new ArrayList<>();
        for (CommerceMode mode : modes == null || modes.isEmpty()
                ? List.of(CommerceMode.values()) : modes) {
            modeNames.add(mode.name());
        }

        String text = query == null ? "" : query.trim();
        boolean searching = text.length() >= 2;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("shopId", shopId)
                .addValue("modes", modeNames)
                .addValue("pattern", searching ? "%" + text.toLowerCase(Locale.ROOT) + "%" : null)
                .addValue("limit", size)
                .addValue("offset", Math.multiplyExact((long) page, size));

        // COALESCE on commerce_mode because rows written before V74 have NULL
        // there and are online listings by definition - the backfill set them,
        // but a row inserted by an older deployment mid-rollout would not be.
        String where = """
                spv.shop_id = :shopId
                  AND COALESCE(spv.commerce_mode, 'ONLINE_PURCHASE') IN (:modes)
                """
                + (searching ? """
                  AND (lower(p.name) LIKE :pattern
                    OR lower(p.brand) LIKE :pattern
                    OR lower(c.name) LIKE :pattern)
                """ : "");

        // The gallery join is LATERAL and LIMIT 1 rather than a plain join so
        // a product with eight photos still yields one row per listing. It
        // rides idx_product_images_product_sort, so it costs one index probe
        // per row on the page, not a scan.
        String from = """
                  FROM shop_product_variants spv
                  JOIN product_variants v ON v.id = spv.product_variant_id
                  JOIN products p ON p.id = v.product_id
                  LEFT JOIN categories c ON c.id = p.category_id
                  LEFT JOIN inventory inv ON inv.shop_id = spv.shop_id
                                         AND inv.product_variant_id = spv.product_variant_id
                 WHERE %s
                """.formatted(where);

        String fromWithGallery = """
                  FROM shop_product_variants spv
                  JOIN product_variants v ON v.id = spv.product_variant_id
                  JOIN products p ON p.id = v.product_id
                  LEFT JOIN categories c ON c.id = p.category_id
                  LEFT JOIN inventory inv ON inv.shop_id = spv.shop_id
                                         AND inv.product_variant_id = spv.product_variant_id
                  LEFT JOIN LATERAL (
                        SELECT pi.image_url
                          FROM product_images pi
                         WHERE pi.product_id = p.id
                         ORDER BY pi.sort_order, pi.id
                         LIMIT 1
                  ) gallery ON true
                 WHERE %s
                """.formatted(where);

        List<CatalogueItem> content = jdbc.query("""
                SELECT spv.product_variant_id, p.id AS product_id, p.name, p.brand,
                       COALESCE(v.image_url, gallery.image_url) AS image_url,
                       c.id AS category_id, c.name AS category_name, v.unit AS variant_label,
                       spv.selling_price, spv.mrp, spv.price_max, spv.price_mode,
                       COALESCE(spv.commerce_mode, 'ONLINE_PURCHASE') AS commerce_mode,
                       spv.offline_availability, spv.service_duration_minutes,
                       spv.available, spv.active, inv.stock
                %s ORDER BY p.name, spv.product_variant_id
                LIMIT :limit OFFSET :offset
                """.formatted(fromWithGallery), params, (rs, row) -> {
            CommerceMode mode = CommerceMode.valueOf(rs.getString("commerce_mode"));
            return new CatalogueItem(
                    rs.getLong("product_variant_id"), rs.getLong("product_id"),
                    rs.getString("name"), rs.getString("brand"), rs.getString("image_url"),
                    (Long) rs.getObject("category_id"), rs.getString("category_name"),
                    rs.getString("variant_label"),
                    rs.getBigDecimal("selling_price"), rs.getBigDecimal("mrp"),
                    rs.getBigDecimal("price_max"), rs.getString("price_mode"),
                    mode.name(), mode.customerLabel(),
                    rs.getString("offline_availability"),
                    (Integer) rs.getObject("service_duration_minutes"),
                    (Boolean) rs.getObject("available"), (Boolean) rs.getObject("active"),
                    (Integer) rs.getObject("stock"));
        });

        Long total = jdbc.queryForObject("SELECT count(*) " + from, params, Long.class);
        long count = total == null ? 0 : total;
        return new CataloguePage(List.copyOf(content), page, size, count,
                count == 0 ? 0 : (int) Math.ceil((double) count / size));
    }

    /** How many listings this shop has in each commerce mode, in one statement. */
    @Transactional(readOnly = true)
    public Map<String, Long> countsByMode() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (CommerceMode mode : CommerceMode.values()) {
            counts.put(mode.name(), 0L);
        }
        jdbc.query("""
                SELECT COALESCE(spv.commerce_mode, 'ONLINE_PURCHASE') AS mode, count(*) AS total
                  FROM shop_product_variants spv
                 WHERE spv.shop_id = :shopId
                 GROUP BY COALESCE(spv.commerce_mode, 'ONLINE_PURCHASE')
                """, new MapSqlParameterSource("shopId", currentShopId()),
                rs -> { counts.put(rs.getString("mode"), rs.getLong("total")); });
        return Map.copyOf(counts);
    }

    /**
     * The shop this request is for.
     *
     * <p>REFUSED RATHER THAN DEFAULTED when there is no scope. A read that
     * silently fell back to "some shop" would show one merchant another's
     * shelf, which is the failure this whole architecture exists to prevent.
     */
    private static Long currentShopId() {
        var scope = TenantContext.current();
        Long shopId = scope == null ? null : scope.shopId();
        if (shopId == null) {
            throw new IllegalStateException("No shop in scope for a shop catalogue read");
        }
        return shopId;
    }
}
