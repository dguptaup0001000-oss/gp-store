-- A marketplace taxonomy, without rewriting the one that is already in use.
--
-- WHAT WAS WRONG. categories is a flat list. On a kirana that was fine - a
-- shopkeeper picking "Atta, Rice & Dal" from thirty rows is not a problem. On
-- a marketplace it is: a phone merchant adding a handset scrolls past Atta,
-- Baby Care, Beverages, Biscuits and the rest of somebody else's shop to find
-- "Mobile Phones", and there is no way to say that Mobile Phones belongs under
-- Electronics rather than beside Dairy & Eggs.
--
-- WHAT THIS DOES NOT DO. It does not re-parent a single existing row. Those
-- categories are live production data attached to real listings, and deciding
-- that "Oils & Ghee" now sits under a "Grocery & Essentials" node invented in
-- a migration would be this change quietly reorganising a working catalogue.
-- Every existing category keeps parent_id NULL and therefore stays exactly
-- where it is: a top-level category, as it is today.
--
-- WHAT IT ENABLES. New categories can be created under a parent, and the
-- existing ones can be organised deliberately later - by the Super Admin, over
-- time, with the shop owners' knowledge - rather than by a script tonight.
-- Search works across the whole set either way, which is the part merchants
-- feel immediately.
ALTER TABLE categories ADD COLUMN IF NOT EXISTS parent_id BIGINT;

-- Self-referencing and nullable. ON DELETE SET NULL rather than CASCADE: if a
-- parent is ever removed its children must become top-level, not vanish along
-- with the listings that point at them.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_name = 'fk_categories_parent'
          AND table_name = 'categories'
    ) THEN
        ALTER TABLE categories
            ADD CONSTRAINT fk_categories_parent
            FOREIGN KEY (parent_id) REFERENCES categories (id) ON DELETE SET NULL;
    END IF;
END
$$;

-- "Show me the children of X" and "show me the top level" are the two reads
-- every category picker does.
CREATE INDEX IF NOT EXISTS idx_categories_parent
    ON categories (parent_id) WHERE parent_id IS NOT NULL;

-- THE PICKER SEARCHES BY NAME, partially and case-insensitively, over
-- thousands of rows. A b-tree cannot serve a leading wildcard; trigram can.
-- The operator class is resolved rather than assumed for the reason V28
-- documents: production is a Supabase dump where pg_trgm lives in schema
-- "extensions", and a hardcoded gin_trgm_ops fails the deploy there.
DO $$
DECLARE
    opclass text;
BEGIN
    SELECT format('%I.%I', n.nspname, oc.opcname)
      INTO opclass
    FROM pg_opclass oc
    JOIN pg_am am ON am.oid = oc.opcmethod
    JOIN pg_namespace n ON n.oid = oc.opcnamespace
    WHERE oc.opcname = 'gin_trgm_ops'
      AND am.amname = 'gin'
    ORDER BY CASE n.nspname WHEN 'extensions' THEN 0 ELSE 1 END
    LIMIT 1;

    IF opclass IS NULL THEN
        RAISE NOTICE 'gin_trgm_ops not found; skipping category name index';
        RETURN;
    END IF;

    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS idx_categories_name_trgm '
        || 'ON categories USING gin (lower(name) %s)', opclass);
END
$$;

-- "Which categories does this shop already sell in" is the first thing the
-- merchant picker shows, so it must not scan the shop's whole shelf.
CREATE INDEX IF NOT EXISTS idx_spv_shop_variant
    ON shop_product_variants (shop_id, product_variant_id);
