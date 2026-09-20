package com.gpstore.catalog.shop;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The marketplace's own product feed: what is for sale near this customer,
 * across every shop that would serve them.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>Every customer browse path in GP-STORE was scoped to exactly one shop.
 * {@code /api/products/feed} requires a listing, listings are
 * {@link com.gpstore.platform.ShopOwned}, and the tenant filter narrows them
 * to the shop on the thread - so a customer who had chosen no shop fell
 * through TenantResolver to Shop #1 and saw Shop #1's shelf. On a deployment
 * whose Shop #1 is a kirana, that is why the home screen showed groceries; on
 * one whose Shop #1 has no listings, it is why the home screen showed
 * "No products available yet" while shops full of stock sat a street away.
 *
 * <p>That was never a query bug or an empty-state bug. There simply was no
 * marketplace-wide feed, and no amount of fixing the shop-scoped one produces
 * one. This is that missing endpoint.
 *
 * <h2>Why it is JDBC and not JPA</h2>
 *
 * <p>Deliberately, and for the same reason the batched storefront reads are
 * native: JPQL against {@code ShopProductVariant} carries the shop filter, so
 * it can only ever answer about one shop - exactly the thing this has to stop
 * doing. The narrowing that replaces the filter is written into the statement
 * where it can be read: {@code spv.shop_id IN (:shopIds)}, and the ids come
 * from ShopDiscovery, which is the existing authority on which shops a
 * customer may see.
 *
 * <p>SO ELIGIBILITY IS NOT RE-IMPLEMENTED HERE. Who delivers where, which
 * statuses are visible, how far a shop travels - all of that stays in
 * ShopDiscovery. A second copy would drift, and the copy customers browse
 * drifting away from the copy checkout trusts is how a customer is shown a
 * shop that refuses them at the till.
 *
 * <h2>One statement, however many shops</h2>
 *
 * <p>The discovery endpoint's N+1 cost this application its ceiling once
 * already. This does its grouping, its ranking and its paging in the database
 * and returns exactly one page of rows.
 */
@Repository
public class MarketplaceFeedRepository {

    private final JdbcTemplate jdbc;

    public MarketplaceFeedRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A page of the marketplace, newest-and-nearest first, one row per product.
     *
     * <p>DISTINCT ON PICKS THE CARD'S SELLER, and the inner ORDER BY is what
     * decides which one: nearest first, then cheaper, then lowest listing id
     * so the choice is stable across pages. Stability matters more than it
     * sounds - infinite scroll re-queries by offset, and a representative that
     * changes between page 1 and page 2 shows the customer the same product
     * twice or skips one entirely.
     *
     * <p>THE ORDER IS NOT A RECOMMENDATION AND NOT A SALE. It is distance,
     * which is the thing a local marketplace can honestly claim to know.
     * Nothing here reads a payment, a promotion or a sponsorship, and if
     * promoted placement is ever added it must arrive as a visibly separate,
     * labelled thing rather than as a thumb on this scale.
     *
     * @param shopIds the shops this customer may see, from ShopDiscovery.
     *                Empty means no shop serves them, which is a real answer
     *                and returns nothing rather than everything.
     * @param modes   which commerce modes to include - Buy Online, Visit to
     *                Buy, Service at Shop, or any combination.
     */
    public List<Object[]> page(Collection<Long> shopIds,
                               Collection<CommerceMode> modes,
                               Long categoryId,
                               Map<Long, Double> distanceByShop,
                               int limit,
                               int offset) {
        if (shopIds == null || shopIds.isEmpty() || modes == null || modes.isEmpty()) {
            return List.of();
        }
        List<Object> args = new ArrayList<>();
        String shopPlaceholders = placeholders(shopIds.size());
        String modePlaceholders = placeholders(modes.size());

        // The distance each shop is from the customer, handed to the database
        // as a VALUES list so the ranking happens where the paging happens.
        // Computing it here rather than in SQL keeps ONE haversine in the
        // application - ShopDiscovery's - instead of a second one that would
        // slowly disagree with it.
        StringBuilder distances = new StringBuilder();
        for (Long shopId : shopIds) {
            if (distances.length() > 0) {
                distances.append(", ");
            }
            distances.append("(?::bigint, ?::double precision)");
            args.add(shopId);
            args.add(distanceByShop.getOrDefault(shopId, Double.MAX_VALUE));
        }

        String sql = """
                WITH near AS (SELECT * FROM (VALUES %s) AS d(shop_id, distance_km)),
                picked AS (
                  SELECT DISTINCT ON (p.id)
                         p.id                AS product_id,
                         p.name              AS product_name,
                         p.brand             AS brand,
                         c.id                AS category_id,
                         c.name              AS category_name,
                         v.id                AS variant_id,
                         v.quantity          AS variant_quantity,
                         v.unit              AS variant_unit,
                         spv.selling_price   AS selling_price,
                         spv.mrp             AS mrp,
                         spv.price_max       AS price_max,
                         spv.price_mode      AS price_mode,
                         spv.commerce_mode   AS commerce_mode,
                         spv.offline_availability AS offline_availability,
                         spv.service_duration_minutes AS service_duration_minutes,
                         s.id                AS shop_id,
                         s.display_name      AS shop_name,
                         near.distance_km    AS distance_km
                    FROM shop_product_variants spv
                    JOIN near            ON near.shop_id = spv.shop_id
                    JOIN shops s         ON s.id = spv.shop_id
                    JOIN product_variants v ON v.id = spv.product_variant_id
                    JOIN products p      ON p.id = v.product_id
                    LEFT JOIN categories c ON c.id = p.category_id
                   WHERE spv.shop_id IN (%s)
                     AND spv.commerce_mode IN (%s)
                     AND spv.available = true
                     AND COALESCE(spv.active, true) = true
                     AND spv.selling_price IS NOT NULL
                     AND spv.selling_price > 0
                     AND v.available = true
                     AND COALESCE(v.active, true) = true
                     AND p.active = true
                     AND (CAST(? AS bigint) IS NULL OR p.category_id = CAST(? AS bigint))
                   ORDER BY p.id, near.distance_km ASC, spv.selling_price ASC, spv.id ASC
                ),
                sellers AS (
                  SELECT p2.id AS product_id, count(DISTINCT spv2.shop_id) AS seller_count
                    FROM shop_product_variants spv2
                    JOIN product_variants v2 ON v2.id = spv2.product_variant_id
                    JOIN products p2 ON p2.id = v2.product_id
                   WHERE spv2.shop_id IN (%s)
                     AND spv2.commerce_mode IN (%s)
                     AND spv2.available = true
                     AND COALESCE(spv2.active, true) = true
                   GROUP BY p2.id
                )
                SELECT picked.*, COALESCE(sellers.seller_count, 1) AS seller_count
                  FROM picked
                  LEFT JOIN sellers ON sellers.product_id = picked.product_id
                 ORDER BY picked.distance_km ASC, picked.product_id ASC
                 LIMIT ? OFFSET ?
                """.formatted(distances, shopPlaceholders, modePlaceholders,
                shopPlaceholders, modePlaceholders);

        args.addAll(shopIds);
        for (CommerceMode mode : modes) {
            args.add(mode.name());
        }
        args.add(categoryId);
        args.add(categoryId);
        args.addAll(shopIds);
        for (CommerceMode mode : modes) {
            args.add(mode.name());
        }
        args.add(limit);
        args.add(offset);

        return jdbc.query(sql, MarketplaceFeedRepository::card, args.toArray());
    }

    /**
     * A search across the whole marketplace, and across all three modes.
     *
     * <p>SEARCH HAD THE SAME BUG THE FEED DID. Every search route in GP-STORE
     * runs through {@code findSellable(requireListing())}, listings are
     * {@link com.gpstore.platform.ShopOwned}, and the tenant filter narrows
     * them to one shop - so a customer with no shop chosen was searching Shop
     * #1's shelf and being told the town does not sell paracetamol. Fixing
     * the ranking or the typo-tolerance of that query could never have found
     * it, because it was never looking in the right place.
     *
     * <p>ACROSS THE MODES, NOT WITHIN ONE. Somebody typing "haircut" wants
     * the barber, and somebody typing "gold chain" wants the jeweller they
     * have to visit - neither is reachable from a search that only looks at
     * what can be put in a cart. The mode rides on every row so the results
     * can be labelled; it is not a reason to drop one.
     *
     * <p>ONE CARD PER PRODUCT, like the feed, and for the same reason: five
     * shops stocking the same painkiller is one result. The seller count says
     * how many, and tapping it opens the rest.
     *
     * <p>MATCHING IS LEFT TO POSTGRES and kept deliberately plain - name,
     * brand and category, case-insensitively, on each word typed. It is not
     * trying to out-think the existing SmartSearchService; it is answering
     * the question that service cannot reach, which is what the TOWN sells.
     */
    public List<Object[]> search(String keyword,
                                 Collection<Long> shopIds,
                                 Collection<CommerceMode> modes,
                                 Map<Long, Double> distanceByShop,
                                 int limit,
                                 int offset) {
        if (shopIds == null || shopIds.isEmpty() || modes == null || modes.isEmpty()) {
            return List.of();
        }
        List<String> words = wordsOf(keyword);
        if (words.isEmpty()) {
            return List.of();
        }

        List<Object> args = new ArrayList<>();
        StringBuilder distances = new StringBuilder();
        for (Long shopId : shopIds) {
            if (distances.length() > 0) {
                distances.append(", ");
            }
            distances.append("(?::bigint, ?::double precision)");
            args.add(shopId);
            args.add(distanceByShop.getOrDefault(shopId, Double.MAX_VALUE));
        }

        // EVERY WORD MUST MATCH SOMETHING. "blue saree" should not return
        // every saree and everything blue - a customer who typed two words
        // meant both of them.
        StringBuilder matches = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            if (i > 0) {
                matches.append(" AND ");
            }
            matches.append("(p.name ILIKE ? OR p.brand ILIKE ? OR c.name ILIKE ?)");
        }

        String sql = """
                WITH near AS (SELECT * FROM (VALUES %s) AS d(shop_id, distance_km)),
                picked AS (
                  SELECT DISTINCT ON (p.id)
                         p.id                AS product_id,
                         p.name              AS product_name,
                         p.brand             AS brand,
                         c.id                AS category_id,
                         c.name              AS category_name,
                         v.id                AS variant_id,
                         v.quantity          AS variant_quantity,
                         v.unit              AS variant_unit,
                         spv.selling_price   AS selling_price,
                         spv.mrp             AS mrp,
                         spv.price_max       AS price_max,
                         spv.price_mode      AS price_mode,
                         spv.commerce_mode   AS commerce_mode,
                         spv.offline_availability AS offline_availability,
                         spv.service_duration_minutes AS service_duration_minutes,
                         s.id                AS shop_id,
                         s.display_name      AS shop_name,
                         near.distance_km    AS distance_km
                    FROM shop_product_variants spv
                    JOIN near            ON near.shop_id = spv.shop_id
                    JOIN shops s         ON s.id = spv.shop_id
                    JOIN product_variants v ON v.id = spv.product_variant_id
                    JOIN products p      ON p.id = v.product_id
                    LEFT JOIN categories c ON c.id = p.category_id
                   WHERE spv.shop_id IN (%s)
                     AND spv.commerce_mode IN (%s)
                     AND spv.available = true
                     AND COALESCE(spv.active, true) = true
                     AND spv.selling_price IS NOT NULL
                     AND spv.selling_price > 0
                     AND v.available = true
                     AND COALESCE(v.active, true) = true
                     AND p.active = true
                     AND (%s)
                   ORDER BY p.id, near.distance_km ASC, spv.selling_price ASC, spv.id ASC
                ),
                sellers AS (
                  SELECT p2.id AS product_id, count(DISTINCT spv2.shop_id) AS seller_count
                    FROM shop_product_variants spv2
                    JOIN product_variants v2 ON v2.id = spv2.product_variant_id
                    JOIN products p2 ON p2.id = v2.product_id
                   WHERE spv2.shop_id IN (%s)
                     AND spv2.commerce_mode IN (%s)
                     AND spv2.available = true
                     AND COALESCE(spv2.active, true) = true
                   GROUP BY p2.id
                )
                SELECT picked.*, COALESCE(sellers.seller_count, 1) AS seller_count
                  FROM picked
                  LEFT JOIN sellers ON sellers.product_id = picked.product_id
                 ORDER BY picked.distance_km ASC, picked.product_id ASC
                 LIMIT ? OFFSET ?
                """.formatted(distances, placeholders(shopIds.size()),
                placeholders(modes.size()), matches,
                placeholders(shopIds.size()), placeholders(modes.size()));

        args.addAll(shopIds);
        for (CommerceMode mode : modes) {
            args.add(mode.name());
        }
        for (String word : words) {
            String like = "%" + word + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        args.addAll(shopIds);
        for (CommerceMode mode : modes) {
            args.add(mode.name());
        }
        args.add(limit);
        args.add(offset);

        return jdbc.query(sql, MarketplaceFeedRepository::card, args.toArray());
    }

    /**
     * The words a customer typed, cleaned up enough to be safe as LIKE terms.
     *
     * <p>The values still travel as bound parameters - this is not what keeps
     * the statement safe. It bounds the WORK: an unbounded number of words
     * would build an unbounded statement, and % or _ left in would turn a
     * customer's typo into a full table scan.
     */
    private static List<String> wordsOf(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        List<String> words = new ArrayList<>();
        for (String piece : keyword.trim().split("\\s+")) {
            String cleaned = piece.replace("%", "").replace("_", "").trim();
            if (cleaned.length() < 2) {
                continue;
            }
            words.add(cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned);
            if (words.size() == MAX_SEARCH_WORDS) {
                break;
            }
        }
        return words;
    }

    /** As many words as anybody types, and a bound on the statement's size. */
    private static final int MAX_SEARCH_WORDS = 6;

    /**
     * Every nearby shop that offers this product, in any mode.
     *
     * <p>THE OTHER HALF OF THE FEED'S DEDUPLICATION. The feed shows one card
     * per product on purpose - five shops selling Coke is one drink, not five
     * results. That is right for browsing and wrong the moment the customer
     * taps it, because "who has it, where, and for how much" is exactly the
     * question they opened the card to ask. So the card collapses and the
     * detail expands, and neither is inventing anything the other hid.
     *
     * <p>CARRIES WHERE TO GO. A Visit-to-Buy listing whose screen cannot say
     * the address is a poster with no shop behind it, so the shop's address,
     * its coordinates and its public support number come back with the price.
     * These are the things a storefront already shows any passer-by - no
     * merchant's private numbers, no takings, no owner details.
     *
     * <p>Bounded like everything else here: shop ids from ShopDiscovery, and
     * an explicit IN, because a filtered query could only answer about one
     * shop and the question spans the town.
     */
    public List<Object[]> offersOf(Long productId,
                                   Collection<Long> shopIds,
                                   Map<Long, Double> distanceByShop) {
        if (productId == null || shopIds == null || shopIds.isEmpty()) {
            return List.of();
        }
        List<Object> args = new ArrayList<>();
        StringBuilder distances = new StringBuilder();
        for (Long shopId : shopIds) {
            if (distances.length() > 0) {
                distances.append(", ");
            }
            distances.append("(?::bigint, ?::double precision)");
            args.add(shopId);
            args.add(distanceByShop.getOrDefault(shopId, Double.MAX_VALUE));
        }

        String sql = """
                WITH near AS (SELECT * FROM (VALUES %s) AS d(shop_id, distance_km))
                SELECT v.id                AS variant_id,
                       v.quantity          AS variant_quantity,
                       v.unit              AS variant_unit,
                       p.id                AS product_id,
                       p.name              AS product_name,
                       p.brand             AS brand,
                       spv.selling_price   AS selling_price,
                       spv.mrp             AS mrp,
                       spv.price_max       AS price_max,
                       spv.price_mode      AS price_mode,
                       spv.commerce_mode   AS commerce_mode,
                       spv.offline_availability AS offline_availability,
                       spv.service_duration_minutes AS service_duration_minutes,
                       s.id                AS shop_id,
                       s.display_name      AS shop_name,
                       s.address_line      AS address_line,
                       s.locality          AS locality,
                       s.city              AS city,
                       s.pincode           AS pincode,
                       s.latitude          AS shop_latitude,
                       s.longitude         AS shop_longitude,
                       s.support_phone     AS support_phone,
                       near.distance_km    AS distance_km
                  FROM shop_product_variants spv
                  JOIN near            ON near.shop_id = spv.shop_id
                  JOIN shops s         ON s.id = spv.shop_id
                  JOIN product_variants v ON v.id = spv.product_variant_id
                  JOIN products p      ON p.id = v.product_id
                 WHERE spv.shop_id IN (%s)
                   AND v.product_id = ?
                   AND spv.available = true
                   AND COALESCE(spv.active, true) = true
                   AND spv.selling_price IS NOT NULL
                   AND spv.selling_price > 0
                   AND v.available = true
                   AND COALESCE(v.active, true) = true
                   AND p.active = true
                 ORDER BY near.distance_km ASC, spv.selling_price ASC, spv.id ASC
                """.formatted(distances, placeholders(shopIds.size()));

        args.addAll(shopIds);
        args.add(productId);

        return jdbc.query(sql, (rs, rowNum) -> new Object[] {
                rs.getLong("variant_id"), rs.getObject("variant_quantity"),
                rs.getString("variant_unit"), rs.getLong("product_id"),
                rs.getString("product_name"), rs.getString("brand"),
                rs.getBigDecimal("selling_price"), rs.getBigDecimal("mrp"),
                rs.getBigDecimal("price_max"), rs.getString("price_mode"),
                rs.getString("commerce_mode"), rs.getString("offline_availability"),
                rs.getObject("service_duration_minutes"),
                rs.getLong("shop_id"), rs.getString("shop_name"),
                rs.getString("address_line"), rs.getString("locality"),
                rs.getString("city"), rs.getString("pincode"),
                rs.getObject("shop_latitude"), rs.getObject("shop_longitude"),
                rs.getString("support_phone"), rs.getDouble("distance_km")
        }, args.toArray());
    }

    /**
     * One card row. SHARED between the feed and the search on purpose: they
     * select the same columns, and two copies of this mapping would drift
     * until a field appeared on one screen and not the other.
     */
    private static Object[] card(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Object[] {
                rs.getLong("product_id"), rs.getString("product_name"), rs.getString("brand"),
                rs.getObject("category_id"), rs.getString("category_name"),
                rs.getLong("variant_id"), rs.getObject("variant_quantity"), rs.getString("variant_unit"),
                rs.getBigDecimal("selling_price"), rs.getBigDecimal("mrp"), rs.getBigDecimal("price_max"),
                rs.getString("price_mode"), rs.getString("commerce_mode"),
                rs.getString("offline_availability"), rs.getObject("service_duration_minutes"),
                rs.getLong("shop_id"), rs.getString("shop_name"), rs.getDouble("distance_km"),
                rs.getInt("seller_count")
        };
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }
}
