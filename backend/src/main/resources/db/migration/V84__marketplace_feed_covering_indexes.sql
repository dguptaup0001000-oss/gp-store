-- The marketplace feed first narrows by the discovered shops, then chooses
-- one representative listing for each (variant, commerce mode). The 4,000-VU
-- capacity investigation sampled 18-20 concurrent executions of this query;
-- without a covering eligible-listing index PostgreSQL repeatedly visited the
-- heap across tens of thousands of candidates to return a 20-card page.
--
-- Both partial predicates exactly match MarketplaceFeedRepository. These are
-- not speculative general-purpose indexes: the first serves the shop-led
-- candidate scan and the second serves the seller count for only the variants
-- that survived pagination.

CREATE INDEX IF NOT EXISTS idx_spv_marketplace_shop_candidates
    ON shop_product_variants
       (shop_id, product_variant_id, commerce_mode, selling_price, id)
    WHERE available = true
      AND COALESCE(active, true) = true
      AND selling_price IS NOT NULL
      AND selling_price > 0;

CREATE INDEX IF NOT EXISTS idx_spv_marketplace_variant_sellers
    ON shop_product_variants
       (product_variant_id, commerce_mode, shop_id)
    WHERE available = true
      AND COALESCE(active, true) = true;
