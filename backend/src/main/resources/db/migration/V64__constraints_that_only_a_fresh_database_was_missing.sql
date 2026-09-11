-- THE CHECK CONSTRAINTS A NEW ENVIRONMENT NEVER GOT.
--
-- WHAT WAS WRONG. This schema is built Hibernate-first: the entity tables come
-- from ddl-auto, and the versioned scripts decorate them afterwards (see
-- FlywayAfterSchemaConfig and db/migration/README.md). That works for
-- everything a migration adds with ALTER TABLE. It does NOT work for anything
-- a migration declares INSIDE a CREATE TABLE IF NOT EXISTS, because against a
-- table Hibernate has already made, that whole statement is a no-op.
--
-- So every semantic CHECK written inside one of those CREATE TABLE bodies has
-- been silently absent on every freshly provisioned database, while being
-- present and enforcing on the shop's own - which is the worst shape a schema
-- difference can take, because the environment you test on is the one without
-- the guard.
--
-- An audit of all 41 migration-created tables against a database built the
-- fresh way found ten such constraints. Everything else declared in those
-- bodies turned out to be covered: Hibernate emits its own CHECK for every
-- @Enumerated column, with the same value set, so ck_dues_status,
-- ck_billing_plan_tier, ck_billing_period_status, ck_ledger_entry_type,
-- ck_governance_level and ck_governance_appeal_outcome are enforced under
-- another name and are deliberately NOT duplicated here.
--
-- WHY A NEW MIGRATION RATHER THAN DROPPING AND RECREATING THE TABLES.
-- FlywayOwnedTableReset can make Hibernate stand aside for a table, and that
-- is right for a table nothing else references - it is how shop_business_hours
-- and shop_hours_override were fixed. It is wrong here: dropping
-- shop_product_variants or billing_plan CASCADE would take the foreign keys of
-- surviving tables with it, and nothing would put those back, because
-- Hibernate has already run by then. Adding the constraints is additive,
-- reversible and touches no data.
--
-- SAFE ON A DATABASE THAT ALREADY HAS THEM. Each is guarded on its own name,
-- so on the shop's database this migration finds all ten present and does
-- nothing. It cannot fail on existing rows either: where a constraint is
-- present it has been enforcing since the migration that created it, and
-- where it is absent the database is a new one with nothing in it. The VERIFY
-- block at the end refuses to let the migration pass unless all ten exist.

DO $$
DECLARE
    -- name, table, and the predicate, transcribed from the CREATE TABLE that
    -- declared it. The predicates are copied verbatim so the two databases
    -- cannot end up enforcing subtly different rules under one name.
    wanted CONSTANT text[][] := ARRAY[
        -- A billing week ends on or after it starts.
        ['ck_billing_period_dates', 'billing_period', 'ends_on >= starts_on'],
        -- A commission rate is a proportion: 0 to 100% in basis points.
        ['ck_billing_plan_bps', 'billing_plan', 'commission_bps >= 0 AND commission_bps <= 10000'],
        -- A plan that has ended, ended after it began.
        ['ck_billing_plan_dates', 'billing_plan', 'effective_to IS NULL OR effective_to > effective_from'],
        -- A weekly fee is not negative. THE AMOUNT ITSELF IS STILL UNDECIDED -
        -- this says only that whatever it becomes cannot be less than nothing.
        ['ck_billing_plan_fee', 'billing_plan', 'weekly_fee >= 0'],
        -- A debt of zero is not a debt.
        ['ck_dues_amount', 'customer_cancellation_dues', 'amount > 0'],
        -- Two preferred shops per category, first and second. Part 2 §4.
        ['ck_preferred_slot', 'customer_preferred_shops', 'slot IN (1, 2)'],
        -- An appeal cannot have an outcome if nobody appealed.
        ['ck_governance_outcome_needs_an_appeal', 'merchant_governance_actions',
         'appeal_outcome IS NULL OR appealed_at IS NOT NULL'],
        -- A shop cannot list something at zero or less. THE FLOOR IS ZERO, NOT
        -- A FIGURE: there is no platform minimum price and this does not
        -- introduce one.
        ['shop_product_variant_price_positive', 'shop_product_variants', 'selling_price > 0'],
        -- Stars are one to five.
        ['ck_shop_rating_range', 'shop_ratings', 'rating BETWEEN 1 AND 5'],
        -- A hidden rating has a reason, and a reason means it is hidden. The
        -- enum check Hibernate emits constrains WHICH reason; this constrains
        -- whether there is one at all, which is the half that was missing.
        ['ck_shop_rating_hidden_has_a_reason', 'shop_ratings',
         '(hidden_at IS NULL) = (hidden_reason IS NULL)']
    ];
    name text;
    tbl  text;
    pred text;
    added int := 0;
    i int;
BEGIN
    FOR i IN 1 .. array_length(wanted, 1) LOOP
        name := wanted[i][1];
        tbl  := wanted[i][2];
        pred := wanted[i][3];

        -- A table that is not here at all is not this migration's business.
        CONTINUE WHEN to_regclass(tbl) IS NULL;

        IF NOT EXISTS (
            SELECT 1 FROM pg_constraint
            WHERE conname = name AND conrelid = tbl::regclass
        ) THEN
            EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I CHECK (%s)',
                           tbl, name, pred);
            added := added + 1;
            RAISE NOTICE 'V64: added % on %', name, tbl;
        END IF;
    END LOOP;

    RAISE NOTICE 'V64: % constraint(s) added (0 means this database already had them).', added;
END $$;

-- VERIFY. The migration does not get to report success on a database that is
-- still missing one of them.
DO $$
DECLARE
    missing text[];
BEGIN
    SELECT array_agg(c.name ORDER BY c.name) INTO missing
    FROM (VALUES
        ('ck_billing_period_dates', 'billing_period'),
        ('ck_billing_plan_bps', 'billing_plan'),
        ('ck_billing_plan_dates', 'billing_plan'),
        ('ck_billing_plan_fee', 'billing_plan'),
        ('ck_dues_amount', 'customer_cancellation_dues'),
        ('ck_preferred_slot', 'customer_preferred_shops'),
        ('ck_governance_outcome_needs_an_appeal', 'merchant_governance_actions'),
        ('shop_product_variant_price_positive', 'shop_product_variants'),
        ('ck_shop_rating_range', 'shop_ratings'),
        ('ck_shop_rating_hidden_has_a_reason', 'shop_ratings')
    ) AS c(name, tbl)
    WHERE to_regclass(c.tbl) IS NOT NULL
      AND NOT EXISTS (
          SELECT 1 FROM pg_constraint
          WHERE conname = c.name AND conrelid = c.tbl::regclass
      );

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V64: these constraints are still missing after the migration ran: %. '
            'A new environment would run without them while the shop runs with '
            'them, which is the difference this migration exists to remove.',
            array_to_string(missing, ', ');
    END IF;
END $$;
