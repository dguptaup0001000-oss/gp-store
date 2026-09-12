-- V63: every payment says who collected it.
--
-- THE GAP THIS CLOSES. PaymentCollection was built as the seam between an
-- order and whoever receives the money for it, and it was reachable from
-- exactly one place: a read-only endpoint that tells a merchant, in words,
-- who collects. Nothing on the actual payment path consulted it. The path
-- hard-coded the provider and recorded nothing at all about the collection
-- model, which means a deployment that changed the model later would have no
-- way to tell, for any historical payment, which account the money had gone
-- to. That is not a theoretical problem: it is the question every settlement
-- dispute and every reconciliation starts with.
--
-- NOT A PROVIDER DECISION. This column records what is ALREADY TRUE - today,
-- for every row, PLATFORM_COLLECTS - and it invents no merchant accounts, no
-- gateway, no settlement mechanism and no regulatory position. Those remain
-- undecided and are deliberately not represented here. What changes is that
-- the fact becomes recorded rather than assumed.
--
-- NULLABLE, AND LEFT NULL FOR HISTORY. A backfill would be a guess written
-- into a financial record. Every payment taken before this migration was
-- collected under the only model that has ever been implemented, and saying
-- so in a comment is honest; stamping it into a column as though somebody
-- had observed it is not.
ALTER TABLE payments ADD COLUMN IF NOT EXISTS collection_model VARCHAR(30);

COMMENT ON COLUMN payments.collection_model IS
    'Who collected this payment, resolved from PaymentCollection at the moment '
    'checkout was prepared. NULL on rows that predate V63. Not a provider name - '
    'the provider is in the provider column; this is the settlement model.';

-- ------------------------------------------------------------------ VERIFY
DO $$
DECLARE
    n BIGINT;
BEGIN
    SELECT count(*) INTO n FROM payments WHERE collection_model IS NOT NULL;
    IF n > 0 THEN
        RAISE EXCEPTION 'V63: % payment(s) were stamped with a collection model by the '
                        'migration. Nobody observed how those payments were collected, and '
                        'writing a guess into a financial record is worse than leaving it '
                        'blank.', n;
    END IF;
END $$;
