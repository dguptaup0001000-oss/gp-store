-- Delivery coupons: knock up to N rupees off the quoted delivery fee
-- (₹10 delivery → ₹0, ₹20 delivery → ₹10). Merchandise FLAT/PERCENTAGE
-- coupons are unchanged.
--
-- Hibernate's CHECK on coupons.discount_type lists the enum as it was when
-- the table was first created, and ddl-auto=update never widens it. Same
-- pattern as V20 for order_status: discover the constraint by definition,
-- drop it, recreate with every current value.

DO $$
DECLARE
    constraint_name TEXT;
BEGIN
    FOR constraint_name IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
        WHERE nsp.nspname = current_schema()
          AND rel.relname = 'coupons'
          AND con.contype = 'c'
          AND pg_get_constraintdef(con.oid) ILIKE '%discount_type%'
    LOOP
        EXECUTE format('ALTER TABLE coupons DROP CONSTRAINT %I', constraint_name);
    END LOOP;
END $$;

ALTER TABLE coupons ADD CONSTRAINT coupons_discount_type_check
    CHECK (discount_type IS NULL OR discount_type IN (
        'FLAT',
        'PERCENTAGE',
        'DELIVERY_FLAT'
    ));

-- Shop-ready offer. Skip if the code is already in use so a re-run or a
-- shop that created the same code by hand is not a migrate failure.
INSERT INTO coupons (
    coupon_code,
    discount_type,
    discount_value,
    max_discount_amount,
    minimum_order_amount,
    expiry_date,
    usage_limit,
    used_count,
    active
)
SELECT
    'FREEDEL10',
    'DELIVERY_FLAT',
    10.00,
    NULL,
    NULL,
    NULL,
    NULL,
    0,
    TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM coupons WHERE upper(coupon_code) = 'FREEDEL10'
);
