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
     * A page of the marketplace, nearest first, one row per variant and commerce mode.
     *
     * <p>DISTINCT ON PICKS THE CARD'S SELLER, and the inner ORDER BY is what
     * decides which one: nearest first, then cheaper, then lowest listing id
     * so the choice is stable across pages. Stability matters more than it
     * sounds - infinite scroll re-queries by offset, and a representative that
     * changes between page 1 and page 2 shows the customer the same product
     * twice or skips one entirely.
     *
     * <p>THE ORDER IS A FAIR WALK AROUND THE NEARBY SHOPS. The first eligible
     * card from each shop is shown before a second card from any shop; distance
     * orders shops inside each round. Without that round-robin layer, one
     * nearby supermarket with a deep catalogue fills dozens of pages before
     * the customer sees the phone shop next door. Nothing here reads a
     * payment, promotion or sponsorship.
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
        String modePlaceholders = placeholders(modes.size());
        // Build only the prefix needed by this page. The nearest-shop window
        // grows monotonically, so a farther shop introduced on page N cannot
        // replace a representative already returned on an earlier page.
        // The per-shop window also grows with depth; selected-shop infinite
        // scroll therefore remains unbounded by this optimisation.
        int requestedPrefix = Math.max(1, offset + limit);
        int candidateShopLimit = Math.min(shopIds.size(), requestedPrefix + 32);
        int candidatesPerShop = Math.max(64,
                (requestedPrefix + candidateShopLimit - 1) / candidateShopLimit + 64);

        // The distance each shop is from the customer, handed to the database
        // as a VALUES list so the ranking happens where the paging happens.
        // Computing it here rather than in SQL keeps ONE haversine in the
        // application - ShopDiscovery's - instead of a second one that would
        // slowly disagree with it.
        NearInput near = nearInput(shopIds, distanceByShop);
        args.add(near.shopIds());
        args.add(near.distances());

        // PAGE BEFORE ENRICHMENT. Image lookup, inventory and seller counts do
        // not affect which card wins or its ordering. Performing them inside
        // picked used to execute those joins for every eligible listing in a
        // town (tens of thousands on the capacity fixture) merely to return
        // twenty cards. The materialized page freezes the exact old winner
        // and order first; only those bounded rows are then enriched.
        String sql = """
                WITH near AS (
                    SELECT shop_id_text::bigint AS shop_id,
                           distance_text::double precision AS distance_km
                      FROM unnest(string_to_array(?, ','), string_to_array(?, ','))
                           AS d(shop_id_text, distance_text)
                ),
                candidate_near AS MATERIALIZED (
                    SELECT near.*
                      FROM near
                     ORDER BY near.distance_km ASC, near.shop_id ASC
                     LIMIT ?
                ),
                candidate_listings AS MATERIALIZED (
                    SELECT offer.listing_id, offer.product_id, offer.variant_id,
                           offer.commerce_mode, candidate_near.shop_id,
                           candidate_near.distance_km, offer.selling_price
                      FROM candidate_near
                      CROSS JOIN LATERAL (
                          SELECT spv.id AS listing_id, p.id AS product_id,
                                 v.id AS variant_id, spv.commerce_mode,
                                 spv.selling_price
                            FROM shop_product_variants spv
                            JOIN product_variants v ON v.id = spv.product_variant_id
                            JOIN products p ON p.id = v.product_id
                           WHERE spv.shop_id = candidate_near.shop_id
                             AND spv.commerce_mode IN (%s)
                             AND spv.available = true
                             AND COALESCE(spv.active, true) = true
                             AND spv.selling_price IS NOT NULL
                             AND spv.selling_price > 0
                             AND v.available = true
                             AND COALESCE(v.active, true) = true
                             AND p.active = true
                             AND (CAST(? AS bigint) IS NULL OR p.category_id = CAST(? AS bigint))
                           ORDER BY spv.product_variant_id ASC,
                                    spv.commerce_mode ASC, spv.selling_price ASC, spv.id ASC
                           LIMIT ?
                      ) offer
                ),
                picked AS (
                  SELECT DISTINCT ON (variant_id, commerce_mode)
                         listing_id, product_id, variant_id, commerce_mode,
                         shop_id, distance_km
                    FROM candidate_listings
                   ORDER BY variant_id, commerce_mode, distance_km ASC,
                            selling_price ASC, listing_id ASC
                ),
                spread AS (
                    SELECT picked.*,
                           ROW_NUMBER() OVER (
                               PARTITION BY picked.shop_id
                               ORDER BY picked.product_id ASC, picked.commerce_mode ASC
                           ) AS shop_row
                      FROM picked
                ),
                paged AS MATERIALIZED (
                    SELECT spread.*
                      FROM spread
                     ORDER BY spread.shop_row ASC, spread.distance_km ASC,
                              spread.shop_id ASC, spread.product_id ASC,
                              spread.commerce_mode ASC
                     LIMIT ? OFFSET ?
                ),
                sellers AS (
                    SELECT spv2.product_variant_id AS variant_id, spv2.commerce_mode,
                           count(DISTINCT spv2.shop_id) AS seller_count
                    FROM shop_product_variants spv2
                    JOIN near ON near.shop_id = spv2.shop_id
                    JOIN paged ON paged.variant_id = spv2.product_variant_id
                              AND paged.commerce_mode = spv2.commerce_mode
                   WHERE spv2.available = true
                     AND COALESCE(spv2.active, true) = true
                   GROUP BY spv2.product_variant_id, spv2.commerce_mode
                )
                SELECT p.id                AS product_id,
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
                       paged.distance_km   AS distance_km,
                       COALESCE(sellers.seller_count, 1) AS seller_count,
                       COALESCE(product_image.image_url, NULLIF(v.image_url, '')) AS image_url,
                       COALESCE(inv.stock, 0) - COALESCE(inv.reserved_stock, 0) > 0 AS in_stock
                  FROM paged
                  JOIN shop_product_variants spv ON spv.id = paged.listing_id
                  JOIN shops s ON s.id = paged.shop_id
                  JOIN product_variants v ON v.id = paged.variant_id
                  JOIN products p ON p.id = paged.product_id
                  LEFT JOIN categories c ON c.id = p.category_id
                  LEFT JOIN sellers ON sellers.variant_id = paged.variant_id
                                   AND sellers.commerce_mode = paged.commerce_mode
                  LEFT JOIN inventory inv ON inv.shop_id = paged.shop_id
                                         AND inv.product_variant_id = paged.variant_id
                  LEFT JOIN LATERAL (
                    SELECT pi.image_url
                      FROM product_images pi
                     WHERE pi.product_id = paged.product_id
                       AND (pi.product_variant_id = paged.variant_id
                            OR pi.product_variant_id IS NULL)
                     ORDER BY (pi.product_variant_id IS NULL) DESC,
                              (pi.product_variant_id = paged.variant_id) DESC,
                              pi.sort_order ASC, pi.id ASC
                     LIMIT 1
                  ) product_image ON true
                 ORDER BY paged.shop_row ASC, paged.distance_km ASC,
                          paged.shop_id ASC, paged.product_id ASC, paged.commerce_mode ASC
                """.formatted(modePlaceholders);

        args.add(candidateShopLimit);
        for (CommerceMode mode : modes) {
            args.add(mode.name());
        }
        args.add(categoryId);
        args.add(categoryId);
        args.add(candidatesPerShop);
        args.add(limit);
        args.add(offset);

        return queryCardsWithOptionalPlan("feed", sql, args.toArray());
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
     * <p>ONE CARD PER VARIANT, like the feed, and for the same reason: five
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
        return search(List.of(keyword == null ? "" : keyword), shopIds, modes,
                distanceByShop, limit, offset);
    }

    /**
     * Match one or more whole-query alternatives in one database plan. Search
     * synonyms used to run the complete expensive search once for the
     * canonical phrase, then again for the literal phrase whenever the first
     * returned nothing. Progressive radius search multiplied that fallback
     * by every radius rung. Treating the phrases as alternatives keeps the
     * same useful matches while doing one round trip per rung.
     */
    public List<Object[]> search(Collection<String> keywords,
                                 Collection<Long> shopIds,
                                 Collection<CommerceMode> modes,
                                 Map<Long, Double> distanceByShop,
                                 int limit,
                                 int offset) {
        if (shopIds == null || shopIds.isEmpty() || modes == null || modes.isEmpty()) {
            return List.of();
        }
        List<List<String>> alternatives = keywords == null ? List.of() : keywords.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(MarketplaceFeedRepository::wordsOf)
                .filter(words -> !words.isEmpty())
                .distinct()
                .toList();
        if (alternatives.isEmpty()) {
            return List.of();
        }

        List<Object> args = new ArrayList<>();
        NearInput near = nearInput(shopIds, distanceByShop);
        args.add(near.shopIds());
        args.add(near.distances());

        // All words in one phrase must match, while phrases are synonyms and
        // therefore alternatives: "chini" can match a catalog entry called
        // "sugar" without a second copy of this query being executed.
        String productMatches = alternatives.stream().map(words -> "(" +
                String.join(" AND ", java.util.Collections.nCopies(words.size(), """
                        (p.name ILIKE ? OR p.brand ILIKE ? OR c.name ILIKE ?
                         OR p.search_keywords ILIKE ? OR p.subcategory ILIKE ?
                         OR v.sku ILIKE ? OR v.barcode ILIKE ? OR v.unit ILIKE ?)
                        """)) + ")").collect(java.util.stream.Collectors.joining(" OR "));
        String shopMatches = alternatives.stream().map(words -> "(" +
                String.join(" AND ", java.util.Collections.nCopies(words.size(), "s.display_name ILIKE ?")) + ")")
                .collect(java.util.stream.Collectors.joining(" OR "));

        // Keep the same late-enrichment boundary as page(): search matching
        // and representative selection decide the page, then only the bounded
        // result receives image, inventory and seller-count joins.
        String sql = """
                WITH near AS (
                    SELECT shop_id_text::bigint AS shop_id,
                           distance_text::double precision AS distance_km
                      FROM unnest(string_to_array(?, ','), string_to_array(?, ','))
                           AS d(shop_id_text, distance_text)
                ),
                product_matches AS MATERIALIZED (
                    SELECT v.id AS variant_id, p.id AS product_id
                      FROM product_variants v
                      JOIN products p ON p.id = v.product_id
                      LEFT JOIN categories c ON c.id = p.category_id
                     WHERE v.available = true
                       AND COALESCE(v.active, true) = true
                       AND p.active = true
                       AND (%s)
                ),
                matched_shops AS MATERIALIZED (
                    SELECT s.id AS shop_id
                      FROM shops s
                      JOIN near ON near.shop_id = s.id
                     WHERE %s
                ),
                matched_variants AS MATERIALIZED (
                    SELECT product_matches.variant_id, product_matches.product_id
                      FROM product_matches
                    UNION
                    SELECT v.id AS variant_id, p.id AS product_id
                      FROM matched_shops
                      JOIN shop_product_variants spv
                        ON spv.shop_id = matched_shops.shop_id
                      JOIN product_variants v ON v.id = spv.product_variant_id
                      JOIN products p ON p.id = v.product_id
                     WHERE v.available = true
                       AND COALESCE(v.active, true) = true
                       AND p.active = true
                ),
                picked AS (
                  SELECT DISTINCT ON (matched_variants.variant_id, offer.commerce_mode)
                         offer.listing_id    AS listing_id,
                         matched_variants.product_id AS product_id,
                         matched_variants.variant_id AS variant_id,
                         offer.commerce_mode AS commerce_mode,
                         offer.shop_id       AS shop_id,
                         offer.distance_km   AS distance_km
                    FROM matched_variants
                    CROSS JOIN LATERAL (
                        SELECT spv.id AS listing_id, spv.commerce_mode, spv.shop_id,
                               near.distance_km, spv.selling_price
                          FROM shop_product_variants spv
                          JOIN near ON near.shop_id = spv.shop_id
                         WHERE spv.product_variant_id = matched_variants.variant_id
                           AND spv.commerce_mode IN (%s)
                           AND spv.available = true
                           AND COALESCE(spv.active, true) = true
                           AND spv.selling_price IS NOT NULL
                           AND spv.selling_price > 0
                    ) offer
                   ORDER BY matched_variants.variant_id, offer.commerce_mode,
                            offer.distance_km ASC, offer.selling_price ASC, offer.listing_id ASC
                ),
                spread AS (
                    SELECT picked.*,
                           ROW_NUMBER() OVER (
                               PARTITION BY picked.shop_id
                               ORDER BY picked.product_id ASC, picked.commerce_mode ASC
                           ) AS shop_row
                      FROM picked
                ),
                paged AS MATERIALIZED (
                    SELECT spread.*
                      FROM spread
                     ORDER BY spread.shop_row ASC, spread.distance_km ASC,
                              spread.shop_id ASC, spread.product_id ASC,
                              spread.commerce_mode ASC
                     LIMIT ? OFFSET ?
                ),
                sellers AS (
                    SELECT spv2.product_variant_id AS variant_id, spv2.commerce_mode,
                           count(DISTINCT spv2.shop_id) AS seller_count
                    FROM shop_product_variants spv2
                    JOIN near ON near.shop_id = spv2.shop_id
                    JOIN paged ON paged.variant_id = spv2.product_variant_id
                              AND paged.commerce_mode = spv2.commerce_mode
                   WHERE spv2.available = true
                     AND COALESCE(spv2.active, true) = true
                   GROUP BY spv2.product_variant_id, spv2.commerce_mode
                )
                SELECT p.id                AS product_id,
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
                       paged.distance_km   AS distance_km,
                       COALESCE(sellers.seller_count, 1) AS seller_count,
                       COALESCE(product_image.image_url, NULLIF(v.image_url, '')) AS image_url,
                       COALESCE(inv.stock, 0) - COALESCE(inv.reserved_stock, 0) > 0 AS in_stock
                  FROM paged
                  JOIN shop_product_variants spv ON spv.id = paged.listing_id
                  JOIN shops s ON s.id = paged.shop_id
                  JOIN product_variants v ON v.id = paged.variant_id
                  JOIN products p ON p.id = paged.product_id
                  LEFT JOIN categories c ON c.id = p.category_id
                  LEFT JOIN sellers ON sellers.variant_id = paged.variant_id
                                   AND sellers.commerce_mode = paged.commerce_mode
                  LEFT JOIN inventory inv ON inv.shop_id = paged.shop_id
                                         AND inv.product_variant_id = paged.variant_id
                  LEFT JOIN LATERAL (
                    SELECT pi.image_url
                      FROM product_images pi
                     WHERE pi.product_id = paged.product_id
                       AND (pi.product_variant_id = paged.variant_id
                            OR pi.product_variant_id IS NULL)
                     ORDER BY (pi.product_variant_id IS NULL) DESC,
                              (pi.product_variant_id = paged.variant_id) DESC,
                              pi.sort_order ASC, pi.id ASC
                     LIMIT 1
                  ) product_image ON true
                 ORDER BY paged.shop_row ASC, paged.distance_km ASC,
                          paged.shop_id ASC, paged.product_id ASC, paged.commerce_mode ASC
                """.formatted(productMatches, shopMatches,
                        placeholders(modes.size()));

        for (List<String> words : alternatives) {
            for (String word : words) {
                String like = "%" + word + "%";
                for (int i = 0; i < 8; i++) args.add(like);
            }
        }
        for (List<String> words : alternatives) {
            for (String word : words) args.add("%" + word + "%");
        }
        for (CommerceMode mode : modes) {
            args.add(mode.name());
        }
        args.add(limit);
        args.add(offset);

        return queryCardsWithOptionalPlan("search", sql, args.toArray());
    }

    /**
     * Large-marketplace CI can print the real feed/search plans for its seeded
     * database by setting a test-only JVM property around two representative
     * reads. This keeps EXPLAIN ANALYZE tied to the SQL and bind parameters the
     * application actually sends, instead of maintaining a hand-copied query.
     */
    private List<Object[]> queryCardsWithOptionalPlan(String name, String sql, Object[] args) {
        if (Boolean.getBoolean("gpstore.test.explain-marketplace")) {
            List<String> plan = jdbc.query(
                    "EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT TEXT) " + sql,
                    (rs, rowNum) -> rs.getString(1), args);
            System.out.println("EXPLAIN ANALYZE marketplace " + name + ":");
            plan.forEach(System.out::println);
        }
        return jdbc.query(sql, MarketplaceFeedRepository::card, args);
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
     * <p>Bounded like everything else here: the {@code near} values come only
     * from ShopDiscovery and are joined directly to the listing's shop. The
     * old duplicate {@code IN (...)} repeated thousands of bind parameters
     * without narrowing the join any further.
     */
    public List<Object[]> offersOf(Long productId,
                                   Long variantId,
                                   Collection<Long> shopIds,
                                   Map<Long, Double> distanceByShop) {
        if (productId == null || shopIds == null || shopIds.isEmpty()) {
            return List.of();
        }
        List<Object> args = new ArrayList<>();
        NearInput near = nearInput(shopIds, distanceByShop);
        args.add(near.shopIds());
        args.add(near.distances());

        String sql = """
                WITH near AS (
                    SELECT shop_id_text::bigint AS shop_id,
                           distance_text::double precision AS distance_km
                      FROM unnest(string_to_array(?, ','), string_to_array(?, ','))
                           AS d(shop_id_text, distance_text)
                )
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
                 WHERE v.product_id = ?
                   AND (?::bigint IS NULL OR v.id = ?::bigint)
                   AND spv.available = true
                   AND COALESCE(spv.active, true) = true
                   AND spv.selling_price IS NOT NULL
                   AND spv.selling_price > 0
                   AND v.available = true
                   AND COALESCE(v.active, true) = true
                   AND p.active = true
                 ORDER BY near.distance_km ASC, spv.selling_price ASC, spv.id ASC
                """;

        args.add(productId);
        args.add(variantId);
        args.add(variantId);

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
                rs.getInt("seller_count"), rs.getString("image_url"), rs.getBoolean("in_stock")
        };
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    /**
     * A stable two-parameter representation of the authorized nearby set.
     *
     * <p>The former VALUES list generated two placeholders per shop. A town
     * with 2,116 eligible shops therefore sent 4,232 bind parameters and a
     * differently shaped SQL statement on every request, so PostgreSQL could
     * not reuse one plan. These comma-separated values remain bound data (not
     * SQL text) and are expanded together by PostgreSQL's multi-array unnest.
     * The collections originate from ShopDiscovery; no client-supplied shop
     * identifier bypasses that authority.
     */
    private static NearInput nearInput(Collection<Long> shopIds,
                                       Map<Long, Double> distanceByShop) {
        StringBuilder ids = new StringBuilder();
        StringBuilder distances = new StringBuilder();
        for (Long shopId : shopIds) {
            if (ids.length() > 0) {
                ids.append(',');
                distances.append(',');
            }
            ids.append(shopId);
            distances.append(distanceByShop.getOrDefault(shopId, Double.MAX_VALUE));
        }
        return new NearInput(ids.toString(), distances.toString());
    }

    private record NearInput(String shopIds, String distances) {}
}
