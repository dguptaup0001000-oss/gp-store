-- Adds indexes on foreign-key and hot-lookup columns that Postgres does NOT
-- auto-create on its own (Postgres only auto-indexes primary keys and columns
-- with an explicit UNIQUE constraint - a plain @ManyToOne/@OneToOne foreign
-- key column gets no index unless one is added explicitly).
--
-- Named V2, not V1: spring.flyway.baseline-on-migrate=true means Flyway
-- treats your current live schema as already being at baseline version 1
-- the first time it runs - a file named V1 would be silently skipped as
-- "already applied". V2 is the first migration that actually executes.
--
-- Every table/column name below was verified directly against the real
-- entity classes (table name from @Table, column name from either an
-- explicit @JoinColumn or Hibernate's default camelCase -> snake_case
-- conversion) - nothing here is guessed.
--
-- Deliberately excluded: customers.email, customers.mobile_number, and
-- payments.transaction_id already have @Column(unique = true) in their
-- entities, which Postgres already backs with a unique index automatically -
-- adding another index on top would be redundant.
--
-- CREATE INDEX IF NOT EXISTS (not CONCURRENTLY): safe and fast at this
-- store's current table sizes. If any of these tables ever grow large enough
-- that a brief write-lock during index creation becomes a real concern, add
-- future indexes with CONCURRENTLY instead - but that requires running
-- outside a transaction, which Flyway's default transactional migrations
-- don't support without extra configuration.

CREATE INDEX IF NOT EXISTS idx_orders_customer_id ON orders (customer_id);
CREATE INDEX IF NOT EXISTS idx_orders_order_status ON orders (order_status);
CREATE INDEX IF NOT EXISTS idx_orders_order_date ON orders (order_date);

CREATE INDEX IF NOT EXISTS idx_order_items_order_id ON order_items (order_id);
CREATE INDEX IF NOT EXISTS idx_order_items_product_variant_id ON order_items (product_variant_id);

CREATE INDEX IF NOT EXISTS idx_cart_items_cart_id ON cart_items (cart_id);
CREATE INDEX IF NOT EXISTS idx_cart_items_product_variant_id ON cart_items (product_variant_id);

CREATE INDEX IF NOT EXISTS idx_products_category_id ON products (category_id);
CREATE INDEX IF NOT EXISTS idx_products_brand ON products (brand);
CREATE INDEX IF NOT EXISTS idx_products_active ON products (active);

CREATE INDEX IF NOT EXISTS idx_product_variants_product_id ON product_variants (product_id);

CREATE INDEX IF NOT EXISTS idx_payments_order_id ON payments (order_id);
CREATE INDEX IF NOT EXISTS idx_payments_payment_status ON payments (payment_status);

CREATE INDEX IF NOT EXISTS idx_notifications_customer_id ON notifications (customer_id);
CREATE INDEX IF NOT EXISTS idx_notifications_order_id ON notifications (order_id);

CREATE INDEX IF NOT EXISTS idx_reviews_customer_id ON reviews (customer_id);
CREATE INDEX IF NOT EXISTS idx_reviews_product_id ON reviews (product_id);

CREATE INDEX IF NOT EXISTS idx_deliveries_order_id ON deliveries (order_id);
CREATE INDEX IF NOT EXISTS idx_deliveries_batch_id ON deliveries (batch_id);

CREATE INDEX IF NOT EXISTS idx_otp_verifications_mobile_number ON otp_verifications (mobile_number);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_token_hash ON refresh_tokens (token_hash);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_customer_id ON refresh_tokens (customer_id);
