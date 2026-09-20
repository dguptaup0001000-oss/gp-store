-- WHAT A VISIT-TO-BUY LISTING CAN HONESTLY BE MEASURED BY.
--
-- An online sale records itself: there is an order, a payment and a receipt,
-- and the merchant's dashboard counts them. A Visit-to-Buy listing and a
-- service have none of that. The customer sees the card, taps Directions,
-- rings the shop, walks in and pays in cash - and GP-STORE never learns
-- whether any of it happened.
--
-- So this table records INTEREST, and nothing but interest: the card was
-- seen, the detail was opened, directions were asked for, the shop was rung.
-- Every one of those is something GP-STORE genuinely observed.
--
-- IT IS NOT A SALES TABLE AND MUST NEVER BE READ AS ONE. Nothing here may be
-- turned into "this merchant sold Rs 1 lakh" or a conversion rate or a
-- commission. Four hundred people asking for directions is four hundred
-- people asking for directions; how many walked in, how many bought and what
-- they paid are facts this platform does not have. Inventing them would mean
-- billing a merchant against a number nobody measured.
CREATE TABLE IF NOT EXISTS listing_engagement_events (
    id                  BIGSERIAL PRIMARY KEY,

    -- SHOP-OWNED, so a merchant's own dashboard reads only their own rows
    -- through the ordinary tenant filter rather than through a hand-written
    -- shop predicate somebody has to remember to add.
    shop_id             BIGINT       NOT NULL,

    product_variant_id  BIGINT       NOT NULL,

    -- VIEWED_CARD / OPENED_DETAIL / ASKED_DIRECTIONS / CALLED_SHOP. Stored as
    -- text rather than a Postgres enum so adding a kind is an application
    -- change, not a migration that locks the table.
    kind                VARCHAR(32)  NOT NULL,

    -- The mode the listing was in WHEN IT WAS SEEN. Copied rather than joined
    -- on purpose: a merchant who switches a listing from Visit-to-Buy to
    -- online next month must not retroactively rewrite what last month's
    -- interest was interest in.
    commerce_mode       VARCHAR(24)  NOT NULL,

    -- NULLABLE, AND THAT IS THE POINT. Anonymous browsing is most of a
    -- marketplace's traffic and an anonymous tap is still a real signal. It
    -- is the only identifier kept: no device id, no IP, no session token, and
    -- nothing that could reconstruct one person's walk through the app.
    customer_id         BIGINT,

    occurred_at         TIMESTAMP    NOT NULL DEFAULT now()
);

-- The merchant's question is "how did this listing do this month", which is
-- shop, then time, then listing.
CREATE INDEX IF NOT EXISTS idx_engagement_shop_time
    ON listing_engagement_events (shop_id, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_engagement_listing
    ON listing_engagement_events (product_variant_id, occurred_at DESC);
