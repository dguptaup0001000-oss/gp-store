-- V61: what happens when a merchant breaks the rules, written down (Part 4 §2).
--
-- SUSPENSION ALREADY EXISTED. merchants.status has carried SUSPENDED since the
-- beginning, and a platform admin could set it. What did not exist was any of
-- the things that make a suspension something other than an arbitrary act: a
-- reason the merchant can read, a record that it happened, a rung below it, a
-- way to answer back, and a rule stopping the ladder being skipped.
--
-- §2 ASKS FOR TRANSPARENCY, and transparency is a schema property before it is
-- a policy one. A status column can say SUSPENDED; it cannot say WHY, WHO,
-- WHEN, ON WHAT EVIDENCE, WHETHER IT WAS APPEALED and WHAT WAS DECIDED. Those
-- are six columns, and without them the honest answer to "why was I
-- suspended" is that nobody knows any more.
--
-- PLATFORM-OWNED, NOT SHOP-OWNED. This is the platform's record ABOUT a
-- merchant, and a merchant may have several shops - so it carries merchant_id
-- and an optional shop_id rather than joining the shop-scoped filter. The
-- merchant's own read is derived from the shop their credential resolved to;
-- see MerchantGovernance.
--
-- NOTHING IS SEEDED. A migration that handed anybody a warning would be the
-- platform disciplining a real kirana for something that never happened.

CREATE TABLE IF NOT EXISTS merchant_governance_actions (
    id                 BIGSERIAL PRIMARY KEY,
    merchant_id        BIGINT       NOT NULL,

    -- NULL means "the whole merchant". A warning about one shop's dispatch
    -- times should not read as a warning about every shop they run.
    --
    -- NOT CALLED shop_id, AND THAT IS DELIBERATE. Every other shop_id in this
    -- schema is a tenancy boundary: the filter narrows on it and the listener
    -- stamps it. This one is DATA - "which shop is this about" - read by the
    -- platform and by the merchant, who owns every shop on their own record.
    -- Naming it shop_id would make it look like isolation while providing
    -- none, which is exactly what ShopScopeIsNotOptionalTest refuses to let
    -- into the schema.
    about_shop_id      BIGINT,

    level              VARCHAR(20)  NOT NULL,
    reason_code        VARCHAR(40)  NOT NULL,

    -- THE EVIDENCE, IN WORDS THE MERCHANT READS. A reason code alone is a
    -- filing category; "14 of your last 40 orders were cancelled after
    -- acceptance" is something a shopkeeper can act on or dispute.
    detail             VARCHAR(1000),

    issued_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    issued_by          VARCHAR(120) NOT NULL,

    -- WARNINGS DECAY. A shop that had a bad fortnight two years ago is not on
    -- a final warning today, and a ladder with no way down is a ladder every
    -- merchant eventually falls off. NULL means it does not expire by itself.
    expires_at         TIMESTAMP,

    -- A reinstatement points at what it lifts, so "suspended, then reinstated"
    -- is one story rather than two unrelated rows.
    supersedes_id      BIGINT REFERENCES merchant_governance_actions (id),

    -- §2's appeal. One per action: a merchant who could appeal repeatedly
    -- could keep a suspension permanently under review.
    appeal_text        VARCHAR(2000),
    appealed_at        TIMESTAMP,
    appeal_outcome     VARCHAR(20),
    appeal_note        VARCHAR(1000),
    appeal_decided_at  TIMESTAMP,
    appeal_decided_by  VARCHAR(120),

    CONSTRAINT ck_governance_level CHECK (level IN
        ('WARNING', 'FINAL_WARNING', 'SUSPENSION', 'TERMINATION', 'REINSTATEMENT')),
    CONSTRAINT ck_governance_appeal_outcome CHECK (appeal_outcome IS NULL OR appeal_outcome IN
        ('UPHELD', 'REDUCED', 'OVERTURNED')),
    -- An outcome without an appeal is a decision about nothing.
    CONSTRAINT ck_governance_outcome_needs_an_appeal
        CHECK (appeal_outcome IS NULL OR appealed_at IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_governance_merchant
    ON merchant_governance_actions (merchant_id, issued_at DESC);
CREATE INDEX IF NOT EXISTS idx_governance_open_appeals
    ON merchant_governance_actions (appealed_at) WHERE appealed_at IS NOT NULL
        AND appeal_outcome IS NULL;

-- ------------------------------------------------------------------ VERIFY
DO $$
DECLARE
    n BIGINT;
BEGIN
    SELECT count(*) INTO n FROM merchant_governance_actions;
    IF n > 0 THEN
        RAISE EXCEPTION 'V61: % governance action(s) were created by the migration. A warning '
                        'nobody issued is the platform disciplining a real kirana for '
                        'something that never happened.', n;
    END IF;
END $$;
