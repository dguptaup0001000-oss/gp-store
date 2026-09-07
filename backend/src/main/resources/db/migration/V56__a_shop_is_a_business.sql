-- V56: the things §3 says a shop has and this one did not.
--
-- WHAT WAS MISSING. A shop had a name, a pin, a radius and some contact
-- details. It had no logo, no policies a customer could read before buying,
-- no business identity, and no verification status - so every storefront on
-- the marketplace looked exactly as trustworthy as every other one, which on
-- a marketplace of strangers is the same as none of them being trustworthy.
--
-- WHAT IS DELIBERATELY NOT HERE: a "trusted" column. §10 says trusted status
-- must be EARNED and must not be purchasable, and the surest way to keep a
-- flag from being sold is for there to be no flag to sell. Trusted is
-- computed from the shop's own trading record (ShopReliability) and has
-- nowhere to be written, by anybody, at any price.

-- --------------------------------------------------- 1. the shop's face
ALTER TABLE shops ADD COLUMN IF NOT EXISTS logo_url VARCHAR(500);

-- ------------------------------------------- 2. the shop's business identity
--
-- PER SHOP, NOT PER MERCHANT, and that is not a detail. A GSTIN and an FSSAI
-- licence are issued against PREMISES: a merchant with three kiranas has
-- three of them, and hanging one off the merchant row would put the wrong
-- licence number on two of their shops' invoices.
ALTER TABLE shops ADD COLUMN IF NOT EXISTS business_name    VARCHAR(200);
ALTER TABLE shops ADD COLUMN IF NOT EXISTS gstin            VARCHAR(20);
ALTER TABLE shops ADD COLUMN IF NOT EXISTS fssai_licence    VARCHAR(30);

-- ------------------------------------------------- 3. what GP-STORE checked
--
-- NONE -> VERIFIED -> BUSINESS_VERIFIED, granted by the platform and by
-- nobody else. The default is NONE for every existing shop including Shop #1:
-- a badge nobody checked is worse than no badge, and backfilling one would be
-- the platform vouching for a shop it has not looked at.
ALTER TABLE shops ADD COLUMN IF NOT EXISTS verification_level VARCHAR(30) NOT NULL DEFAULT 'NONE';
ALTER TABLE shops ADD COLUMN IF NOT EXISTS verified_at        TIMESTAMP;
ALTER TABLE shops ADD COLUMN IF NOT EXISTS verified_by        VARCHAR(120);
ALTER TABLE shops ADD COLUMN IF NOT EXISTS verification_note  VARCHAR(500);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_shops_verification_level') THEN
        ALTER TABLE shops ADD CONSTRAINT ck_shops_verification_level
            CHECK (verification_level IN ('NONE', 'VERIFIED', 'BUSINESS_VERIFIED'));
    END IF;
END $$;

-- ------------------------------------------------------ 4. what it promises
--
-- ROWS, NOT COLUMNS. §3 names three policies today - delivery, cancellation,
-- returns - and a fourth is a row rather than a migration. It also keeps four
-- paragraphs of customer-facing prose out of the shops row, which is read on
-- every discovery call by every customer who opens the app.
CREATE TABLE IF NOT EXISTS shop_policies (
    id         BIGSERIAL PRIMARY KEY,
    shop_id    BIGINT       NOT NULL,

    -- DELIVERY, CANCELLATION, RETURNS. Not an enum type: a new policy kind
    -- should not need a migration on a database somebody is trading on.
    kind       VARCHAR(40)  NOT NULL,

    -- Written for customers, so it is long enough for a shopkeeper to write
    -- like a person rather than to fit a field.
    body       TEXT         NOT NULL,

    updated_at TIMESTAMP,
    updated_by VARCHAR(120),

    created_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_shop_policies_kind ON shop_policies (shop_id, kind);
CREATE INDEX IF NOT EXISTS idx_shop_policies_shop ON shop_policies (shop_id);

-- NO SEED ROWS. A policy GP-STORE wrote and attributed to a shopkeeper is a
-- promise they did not make, and the first time it is tested will be in front
-- of a customer. A shop with no policy shows none, and the readiness
-- checklist is where it gets asked for.

-- ------------------------------------------------------------------ VERIFY
DO $$
DECLARE
    n BIGINT;
BEGIN
    SELECT count(*) INTO n FROM shops WHERE verification_level <> 'NONE';
    IF n > 0 THEN
        RAISE EXCEPTION 'V56: % shop(s) were given a verification badge by the migration. '
                        'A badge nobody checked is the platform vouching for a shop it has '
                        'not looked at.', n;
    END IF;

    SELECT count(*) INTO n FROM shop_policies;
    IF n > 0 THEN
        RAISE EXCEPTION 'V56: % policy row(s) were seeded. A policy GP-STORE wrote and '
                        'attributed to a shopkeeper is a promise they did not make.', n;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema() AND table_name = 'shops'
                 AND column_name IN ('trusted', 'is_trusted')) THEN
        RAISE EXCEPTION 'V56: shops has a trusted column. §10 says trusted must be EARNED and '
                        'not purchasable, and a flag that exists is a flag that can be sold.';
    END IF;
END $$;
