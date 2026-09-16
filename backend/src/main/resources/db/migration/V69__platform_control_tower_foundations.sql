-- Additive foundations for safe platform-wide reads. Legacy customers keep a
-- NULL registration time because the real historical value is unknown.
ALTER TABLE customers ADD COLUMN IF NOT EXISTS created_at TIMESTAMP;

-- Structured context supplements (and does not replace) the existing details
-- field so old audit entries and callers remain valid.
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS merchant_id BIGINT;
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS shop_id BIGINT;
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS previous_state VARCHAR(500);
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS new_state VARCHAR(500);
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS reason VARCHAR(500);
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS request_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_audit_target_time
    ON audit_logs (entity_type, entity_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_merchant_time
    ON audit_logs (merchant_id, occurred_at DESC) WHERE merchant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_audit_shop_time
    ON audit_logs (shop_id, occurred_at DESC) WHERE shop_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_audit_request_id
    ON audit_logs (request_id) WHERE request_id IS NOT NULL;

-- Search uses leading-wildcard case-insensitive matches. The trigram extension
-- is already installed by V5 and is the index family that can serve those
-- queries; ordinary b-trees cannot.
CREATE INDEX IF NOT EXISTS idx_customers_name_trgm
    ON customers USING gin (lower(full_name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_customers_email_trgm
    ON customers USING gin (lower(email) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_customers_phone_trgm
    ON customers USING gin (lower(mobile_number) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_merchants_legal_name_trgm
    ON merchants USING gin (lower(legal_name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_merchants_display_name_trgm
    ON merchants USING gin (lower(display_name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_shops_display_name_trgm
    ON shops USING gin (lower(display_name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_workers_name_trgm
    ON delivery_partners USING gin (lower(name) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_orders_platform_date
    ON orders (order_date DESC, shop_id, order_status);
CREATE INDEX IF NOT EXISTS idx_payments_platform_reference
    ON payments (transaction_id) WHERE transaction_id IS NOT NULL;

-- Application users have no delete/update route for audit rows. Prevent
-- rewriting in place at the database too; corrections are new events. DELETE
-- remains reserved for database retention/fixture maintenance and never has an
-- application endpoint.
CREATE OR REPLACE FUNCTION audit_log_is_not_rewritable() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs is append-oriented: UPDATE is not permitted';
END $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_audit_log_no_update ON audit_logs;
CREATE TRIGGER trg_audit_log_no_update
    BEFORE UPDATE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION audit_log_is_not_rewritable();
