package com.gpstore.rating;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Every read here is shop-scoped by the filter, never by an argument.
 *
 * <p>NOT ONE METHOD TAKES A SHOP ID, and that is the design: a repository
 * method with a shopId parameter is a method some caller will eventually
 * hand a shop id that came off a request. The scope comes from the
 * credential, and a merchant reading their own ratings and a customer
 * reading a storefront's are the same query under two different scopes.
 */
public interface ShopRatingRepository extends JpaRepository<ShopRating, Long> {

    Optional<ShopRating> findByOrderId(Long orderId);

    Page<ShopRating> findByHiddenAtIsNullOrderByCreatedAtDesc(Pageable pageable);

    Page<ShopRating> findByOrderByCreatedAtDesc(Pageable pageable);

    List<ShopRating> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    /**
     * The numbers behind the badge (§19), in one query rather than five.
     *
     * <p>COUNTED OVER THE ROWS THAT COUNT, which is not the same as the rows
     * that are shown - see {@link ShopRating#countsTowardsTheAverage}. A
     * shop's average must not be improvable by moderating.
     *
     * <p>TWO METHODS, NOT ONE WITH A NULLABLE PARAMETER. The obvious shape
     * is a single query with {@code (:since IS NULL OR r.createdAt >= :since)},
     * and Postgres rejects it at prepare time: a bare parameter compared only
     * to NULL has no inferable type, and the driver gets "could not determine
     * data type of parameter $2". Casting round it would work and would leave
     * a piece of dialect-specific noise in a query that reads perfectly well
     * as two.
     */
    @Query("""
            SELECT new com.gpstore.rating.RatingTally(
                       COUNT(r), COALESCE(AVG(CAST(r.rating AS double)), 0.0),
                       SUM(CASE WHEN r.rating = 5 THEN 1 ELSE 0 END),
                       SUM(CASE WHEN r.rating = 4 THEN 1 ELSE 0 END),
                       SUM(CASE WHEN r.rating = 3 THEN 1 ELSE 0 END),
                       SUM(CASE WHEN r.rating = 2 THEN 1 ELSE 0 END),
                       SUM(CASE WHEN r.rating = 1 THEN 1 ELSE 0 END))
            FROM ShopRating r
            WHERE (r.hiddenAt IS NULL OR r.hiddenReason NOT IN
                     (com.gpstore.rating.HideReason.SPAM,
                      com.gpstore.rating.HideReason.IMPERSONATION))
            """)
    RatingTally tallyForever();

    @Query("""
            SELECT new com.gpstore.rating.RatingTally(
                       COUNT(r), COALESCE(AVG(CAST(r.rating AS double)), 0.0),
                       SUM(CASE WHEN r.rating = 5 THEN 1 ELSE 0 END),
                       SUM(CASE WHEN r.rating = 4 THEN 1 ELSE 0 END),
                       SUM(CASE WHEN r.rating = 3 THEN 1 ELSE 0 END),
                       SUM(CASE WHEN r.rating = 2 THEN 1 ELSE 0 END),
                       SUM(CASE WHEN r.rating = 1 THEN 1 ELSE 0 END))
            FROM ShopRating r
            WHERE (r.hiddenAt IS NULL OR r.hiddenReason NOT IN
                     (com.gpstore.rating.HideReason.SPAM,
                      com.gpstore.rating.HideReason.IMPERSONATION))
              AND r.createdAt >= :since
            """)
    RatingTally tallySince(@Param("since") LocalDateTime since);

    /**
     * The stars several shops are showing, in one query.
     *
     * <p>WHY THIS EXISTS BESIDE {@link #tallyForever()}. That one answers for
     * the shop in scope, which is right for a shop's own page and wrong for a
     * list: a discovery screen showing twelve shops would switch scope and ask
     * twelve times, and each of those is three queries. This is the same
     * arithmetic - the same hidden-rating rule, the same average - grouped by
     * shop so a list costs one round trip however long it is.
     *
     * <p>NAMES THE SHOP EXPLICITLY because it is run with the tenant filter
     * off (see PublicShopStars): the ids come from the discovery list the
     * caller already has, and nothing outside them can come back.
     *
     * <p>THE SAME EXCLUSIONS, deliberately duplicated rather than abstracted.
     * If a shop's own page and the list beside it counted different ratings,
     * a customer would see 4.6 on the card and 4.4 on the page - and the bug
     * would look like a rounding error rather than two rules.
     */
    @Query("""
            SELECT r.shopId AS shopId, COUNT(r) AS count,
                   COALESCE(AVG(CAST(r.rating AS double)), 0.0) AS average
            FROM ShopRating r
            WHERE r.shopId IN :shopIds
              AND (r.hiddenAt IS NULL OR r.hiddenReason NOT IN
                     (com.gpstore.rating.HideReason.SPAM,
                      com.gpstore.rating.HideReason.IMPERSONATION))
            GROUP BY r.shopId
            """)
    List<ShopStars> starsByShop(@Param("shopIds") java.util.Collection<Long> shopIds);

    /** One shop's public star rating: the average, and how many said so. */
    interface ShopStars {
        Long getShopId();
        long getCount();
        double getAverage();
    }

    /**
     * How often each reason was given (§18), so a shopkeeper can act on it.
     */
    @Query("""
            SELECT reason, COUNT(r)
            FROM ShopRating r JOIN r.reasons reason
            WHERE (r.hiddenAt IS NULL OR r.hiddenReason NOT IN
                     (com.gpstore.rating.HideReason.SPAM,
                      com.gpstore.rating.HideReason.IMPERSONATION))
              AND r.createdAt >= :since
            GROUP BY reason
            ORDER BY COUNT(r) DESC
            """)
    List<Object[]> reasonCounts(@Param("since") LocalDateTime since);

    Page<ShopRating> findByReportedAtIsNotNullOrderByReportedAtDesc(Pageable pageable);
}
