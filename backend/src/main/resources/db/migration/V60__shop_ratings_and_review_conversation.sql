-- V60: two different questions, asked separately (Part 3 §17-§22).
--
-- "WAS THE ATTA GOOD?" AND "WAS THE SHOP GOOD?" ARE NOT THE SAME QUESTION,
-- and a single star rating answers neither well. A shop that delivered a
-- perfect packet of a mediocre biscuit gets punished for the biscuit; a shop
-- that took three hours to bring an excellent dal gets credit for the dal.
-- §17 is explicit that these are separate, so they are separate rows.
--
-- WHERE EACH ONE LIVES IS NOT SYMMETRIC. A PRODUCT is central (§10): one row
-- for the whole marketplace, and a review of it is a review of the item, so
-- reviews stay central and every shop selling that item shows the same ones.
-- A SHOP rating is about one kirana's own service and is shop-owned - which
-- is also what stops a merchant reading, or answering, a rating left for a
-- competitor.
--
-- §20 IS A SCHEMA DECISION, NOT A POLICY DOCUMENT. "Genuine negative reviews
-- must remain" is only true if removing one is hard, recorded and reasoned.
-- So nothing is deleted: a review is HIDDEN, with a reason from a closed
-- list and the name of whoever hid it, and the row stays for the audit that
-- asks why a shop's one-star reviews keep vanishing.

-- ------------------------------------------------ 1. rating the shop (§17)
CREATE TABLE IF NOT EXISTS shop_ratings (
    id                   BIGSERIAL PRIMARY KEY,
    shop_id              BIGINT      NOT NULL,
    customer_id          BIGINT      NOT NULL,

    -- ONE RATING PER ORDER, which is the whole of §22's first defence: a
    -- rating has to be earned by a transaction, and a transaction can only
    -- be rated once. Bulk one-starring a competitor needs one real order
    -- per star.
    order_id             BIGINT      NOT NULL,

    rating               INTEGER     NOT NULL,
    comment              VARCHAR(1000),

    created_at           TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP,

    -- §21: ONE RESPONSE EACH. Columns, not a thread table, because the rule
    -- is that there is exactly one of each - a table would make "one" a
    -- convention that some future code path forgets.
    merchant_response    VARCHAR(1000),
    merchant_response_at TIMESTAMP,
    merchant_response_by VARCHAR(120),
    customer_reply       VARCHAR(1000),
    customer_reply_at    TIMESTAMP,

    -- §20: hidden, never deleted.
    hidden_at            TIMESTAMP,
    hidden_reason        VARCHAR(40),
    hidden_by            VARCHAR(120),

    -- §22: the shop can report one it believes is abusive. Reporting does
    -- NOT hide it - that is the point. A merchant who could hide a rating by
    -- objecting to it would have a delete button with extra steps.
    reported_at          TIMESTAMP,
    reported_by          VARCHAR(120),
    report_reason        VARCHAR(300),

    CONSTRAINT ck_shop_rating_range CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT ck_shop_rating_hidden_has_a_reason
        CHECK ((hidden_at IS NULL) = (hidden_reason IS NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_shop_rating_per_order ON shop_ratings (order_id);
CREATE INDEX IF NOT EXISTS idx_shop_rating_shop ON shop_ratings (shop_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_shop_rating_customer ON shop_ratings (customer_id);

-- ------------------------------------------------------ 2. the reasons (§18)
--
-- ROWS, NOT A COMMA-SEPARATED COLUMN. "What was wrong" is the half of a
-- rating a shop can actually act on - three stars tells a shopkeeper
-- nothing, three stars and LATE tells them to look at the dispatch times -
-- and a column of joined-up codes cannot be counted without parsing it.
CREATE TABLE IF NOT EXISTS shop_rating_reasons (
    shop_rating_id BIGINT      NOT NULL REFERENCES shop_ratings (id) ON DELETE CASCADE,
    reason         VARCHAR(40) NOT NULL,
    PRIMARY KEY (shop_rating_id, reason)
);

CREATE TABLE IF NOT EXISTS product_review_reasons (
    review_id BIGINT      NOT NULL REFERENCES reviews (id) ON DELETE CASCADE,
    reason    VARCHAR(40) NOT NULL,
    PRIMARY KEY (review_id, reason)
);

-- ------------------------------- 3. the same conversation on a product review
--
-- The columns mirror shop_ratings exactly, deliberately: two shapes for one
-- idea is how the shop side ends up with an appeal route the product side
-- quietly lacks.
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS merchant_response    VARCHAR(1000);
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS merchant_response_at TIMESTAMP;
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS merchant_response_by VARCHAR(120);
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS customer_reply       VARCHAR(1000);
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS customer_reply_at    TIMESTAMP;
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS hidden_at            TIMESTAMP;
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS hidden_reason        VARCHAR(40);
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS hidden_by            VARCHAR(120);
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS reported_at          TIMESTAMP;
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS reported_by          VARCHAR(120);
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS report_reason        VARCHAR(300);

-- WHICH SHOP MAY ANSWER IT. A product review is central, but a reply to one
-- is not: it is one shopkeeper answering a customer who bought from THEM.
-- Null until somebody replies.
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS responding_shop_id   BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_review_hidden_has_a_reason') THEN
        ALTER TABLE reviews ADD CONSTRAINT ck_review_hidden_has_a_reason
            CHECK ((hidden_at IS NULL) = (hidden_reason IS NULL));
    END IF;
END $$;

-- ------------------------------------------------------------------ VERIFY
DO $$
DECLARE
    n BIGINT;
BEGIN
    SELECT count(*) INTO n FROM shop_ratings;
    IF n > 0 THEN
        RAISE EXCEPTION 'V60: % shop rating(s) were invented by the migration. A rating '
                        'nobody left is a rating that misrepresents a real kirana.', n;
    END IF;

    SELECT count(*) INTO n FROM reviews WHERE hidden_at IS NOT NULL;
    IF n > 0 THEN
        RAISE EXCEPTION 'V60: % existing review(s) were hidden by the migration. §20 says '
                        'genuine negatives remain; a migration is not a moderator.', n;
    END IF;

    SELECT count(*) INTO n FROM reviews WHERE merchant_response IS NOT NULL;
    IF n > 0 THEN
        RAISE EXCEPTION 'V60: % review(s) were given a merchant response nobody wrote', n;
    END IF;
END $$;
