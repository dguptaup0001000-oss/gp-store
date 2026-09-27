-- Query support for low-result and merchant intelligence dashboards.

CREATE INDEX IF NOT EXISTS idx_search_events_low_result_time
    ON marketplace_search_events (created_at DESC)
    WHERE result_count BETWEEN 1 AND 2;

CREATE INDEX IF NOT EXISTS idx_orders_shop_delivered_time
    ON orders (shop_id, order_date DESC)
    WHERE order_status = 'DELIVERED';

CREATE INDEX IF NOT EXISTS idx_engagement_kind_time
    ON listing_engagement_events (kind, occurred_at DESC);
