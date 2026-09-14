package com.gpstore.discovery;

import com.gpstore.platform.TenantContext;
import com.gpstore.platform.TenantScope;
import com.gpstore.rating.ShopRatingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The star ratings on a list of shops, in one query.
 *
 * WHY A LIST NEEDS ITS OWN PATH TO THIS. ShopRatingService.summary() answers
 * for the shop in scope, which is exactly right for a shop's own page and
 * costs three queries. A discovery screen showing twelve shops that used it
 * would switch tenant scope twelve times and run thirty-six queries to draw
 * twelve stars - and the list endpoint is the most-hit route in the customer
 * app. So the same arithmetic is asked once, grouped by shop.
 *
 * PLATFORM SCOPE, FOR THE SAME REASON AND WITH THE SAME GUARD AS
 * {@link ShopCategoryPresence}. A shop's public star rating is already public:
 * it is drawn on that shop's storefront to anybody who opens it. The
 * projection is a shop id, a count and an average, so nothing a public caller
 * could not already read comes back, and the shop ids are the ones the caller
 * was already handed by discovery.
 *
 * ABSENT MEANS UNRATED, and every screen must read it that way. A shop with no
 * ratings is missing from the map rather than present with a zero, because
 * zero stars and no stars are different things to a customer choosing a shop -
 * and drawing an unrated shop as nought out of five would be a lie about a
 * real merchant.
 */
@Service
public class PublicShopStars {

    private final ShopRatingRepository ratings;

    public PublicShopStars(ShopRatingRepository ratings) {
        this.ratings = ratings;
    }

    /** The average and the count for each shop that has any, keyed by shop. */
    @Transactional(readOnly = true)
    public Map<Long, Stars> forShops(Collection<Long> shopIds) {
        if (shopIds == null || shopIds.isEmpty()) {
            return Map.of();
        }
        List<ShopRatingRepository.ShopStars> rows =
                TenantContext.runWithin(TenantScope.platform(),
                        () -> ratings.starsByShop(shopIds));

        Map<Long, Stars> byShop = new LinkedHashMap<>();
        for (ShopRatingRepository.ShopStars row : rows) {
            if (row.getShopId() == null || row.getCount() <= 0) {
                continue;
            }
            byShop.put(row.getShopId(), new Stars(roundToOneDecimal(row.getAverage()), row.getCount()));
        }
        return byShop;
    }

    /**
     * ONE DECIMAL PLACE, matching what a shop's own page shows.
     *
     * RatingTally.averageToOneDecimal rounds there; a list that rounded
     * differently would draw 4.6 on the card and 4.55 on the page, and the
     * customer would be right to distrust both.
     */
    private static double roundToOneDecimal(double average) {
        return Math.round(average * 10.0) / 10.0;
    }

    /** What a shop is showing: the average out of five, and how many said so. */
    public record Stars(double average, long count) {}
}
