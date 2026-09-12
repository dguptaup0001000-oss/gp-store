-- V62: "my kirana" is not "my hardware shop" (Part 2 §4).
--
-- THE OBVIOUS DESIGN IS THE WRONG ONE. A single "preferred shop" per customer
-- is what most marketplaces build, and it falls apart the first time somebody
-- buys a bag of atta and a packet of screws in the same week: the shop they
-- trust for groceries has no opinion about screws, and the hardware shop they
-- trust has never sold atta. §4 is explicit - preferences are PER CATEGORY,
-- and they are not one global list.
--
-- TWO SLOTS, ENFORCED BY THE SHAPE RATHER THAN BY A COUNT. A customer may
-- pick up to two shops per category. That could be a service-layer check
-- ("select count(*) ... if >= 2 throw"), which is two requests away from
-- being wrong. Instead each row occupies a numbered SLOT - 1 or 2 - with a
-- unique index on (customer, category, slot) and a CHECK that the slot is one
-- of those two. A third preference has nowhere to go: there is no slot 3 to
-- put it in, and slots 1 and 2 are taken.
--
-- CUSTOMER-OWNED, NOT SHOP-OWNED. This is the customer's list, like their
-- addresses. It carries a shop_id, and that shop_id is DATA - which shop was
-- chosen - not a tenancy boundary, so it is deliberately not the column name
-- a filtered table would use. A merchant must not be able to read who has
-- picked them, or (worse) who has picked their competitor.
--
-- A PREFERENCE IS NOT A CAGE (§4). Nothing in this table hides another shop,
-- blocks a purchase elsewhere, or overrides the customer when a cheaper shop
-- appears. It orders one list. That restraint lives in the service and in
-- MyPreferredShopsTest, because a schema cannot express it.

CREATE TABLE IF NOT EXISTS customer_preferred_shops (
    id                 BIGSERIAL PRIMARY KEY,
    customer_id        BIGINT      NOT NULL REFERENCES customers (id) ON DELETE CASCADE,
    category_id        BIGINT      NOT NULL REFERENCES categories (id) ON DELETE CASCADE,

    -- Which shop was chosen. Data, not a boundary - see above.
    preferred_shop_id  BIGINT      NOT NULL REFERENCES shops (id) ON DELETE CASCADE,

    -- 1 is "first choice", 2 is "second choice". Order is the customer's.
    slot               INTEGER     NOT NULL,

    created_at         TIMESTAMP   NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_preferred_slot CHECK (slot IN (1, 2))
);

-- TWO SLOTS AND NO MORE. This index is the whole of "up to two".
CREATE UNIQUE INDEX IF NOT EXISTS ux_preferred_slot
    ON customer_preferred_shops (customer_id, category_id, slot);

-- AND THE SAME SHOP CANNOT FILL BOTH. Otherwise a customer could "pick two"
-- and get one, which reads to the ranking as a stronger preference than it is.
CREATE UNIQUE INDEX IF NOT EXISTS ux_preferred_shop_once_per_category
    ON customer_preferred_shops (customer_id, category_id, preferred_shop_id);

CREATE INDEX IF NOT EXISTS idx_preferred_by_customer
    ON customer_preferred_shops (customer_id, category_id);

-- ------------------------------------------------------------------ VERIFY
DO $$
DECLARE
    n BIGINT;
BEGIN
    SELECT count(*) INTO n FROM customer_preferred_shops;
    IF n > 0 THEN
        RAISE EXCEPTION 'V62: % preference(s) were chosen by the migration. A preferred shop '
                        'nobody picked is the platform deciding where a customer shops, which '
                        'is the one thing §4 exists to prevent.', n;
    END IF;
END $$;
