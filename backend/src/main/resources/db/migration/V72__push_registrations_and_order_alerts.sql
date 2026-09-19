-- Who gets told about a new order, and once.
--
-- ADDITIVE ONLY. No DROP, no DELETE, no UPDATE, no TRUNCATE. `customers.fcm_token`
-- is deliberately left exactly where it is: the customer app, the worker app and
-- the order-status path still read it, and moving them is a separate change from
-- fixing who a NEW ORDER reaches.
--
-- TWO PROBLEMS, TWO TABLES.
--
-- push_registrations answers "which devices belong to this account, in which app".
-- One nullable column on `customers` could not: it holds a single token, so a
-- merchant signing in on the counter phone that a family member uses as a
-- customer overwrites theirs, signing out leaves the token live, and nothing
-- records which app a token belongs to. A new order must reach the MERCHANT_ADMIN
-- installs of that shop's staff and nothing else, and that is a question about
-- devices, not about people.
--
-- order_alerts_sent answers "has this order already been announced". The unique
-- index is the whole mechanism: a retried checkout, a replayed payment webhook,
-- a redelivered FCM message and a transaction retry all converge on one row, and
-- the second insert loses. Idempotency that lives in the database survives a
-- restart, which idempotency held in memory does not.

CREATE TABLE IF NOT EXISTS push_registrations (
    id              BIGSERIAL PRIMARY KEY,
    customer_id     BIGINT      NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    -- CUSTOMER | MERCHANT_ADMIN | SUPER_ADMIN | WORKER. Kept as text rather than
    -- an enum type so adding an app is a code change and not a migration.
    app             VARCHAR(32) NOT NULL,
    platform        VARCHAR(16) NOT NULL DEFAULT 'ANDROID',
    token           TEXT        NOT NULL,
    -- The install, not the handset. Survives a token rotation, so a refreshed
    -- token updates a row rather than accumulating one.
    device_id       VARCHAR(128),
    enabled         BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at    TIMESTAMP
);

-- ONE ROW PER TOKEN, PLATFORM-WIDE. An FCM token identifies one app install on
-- one device; if it turns up again it has moved to a different account, and the
-- row must MOVE rather than duplicate - otherwise the previous merchant keeps
-- receiving the new one's orders on a phone they no longer use.
CREATE UNIQUE INDEX IF NOT EXISTS ux_push_registration_token
    ON push_registrations (token);

CREATE INDEX IF NOT EXISTS idx_push_registration_account
    ON push_registrations (customer_id, app, enabled);

COMMENT ON TABLE push_registrations IS
    'One row per app install that may be pushed to. Scoped by account and app so a new order reaches only the merchant installs of that shop''s staff.';

-- WHAT HAS ALREADY BEEN ANNOUNCED, AND WHEN.
CREATE TABLE IF NOT EXISTS order_alerts_sent (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT      NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    kind        VARCHAR(40) NOT NULL,
    shop_id     BIGINT,
    recipients  INTEGER     NOT NULL DEFAULT 0,
    sent_at     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- THE IDEMPOTENCY ITSELF. Not a check-then-insert, which two concurrent
-- deliveries both pass; the database refuses the second one.
CREATE UNIQUE INDEX IF NOT EXISTS ux_order_alert_once
    ON order_alerts_sent (order_id, kind);

COMMENT ON TABLE order_alerts_sent IS
    'One row per order per alert kind. The unique index is what makes a retried checkout, a replayed webhook or a redelivered push announce an order once.';
