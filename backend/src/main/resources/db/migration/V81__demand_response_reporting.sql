-- Customer-controlled abuse reporting for the demand network.
-- Reports are retained for audit/moderation; blocks prevent future routing
-- and hide existing responses without deleting commerce history.

CREATE TABLE IF NOT EXISTS demand_response_reports (
    id              BIGSERIAL PRIMARY KEY,
    response_id     BIGINT NOT NULL REFERENCES demand_responses(id),
    customer_id     BIGINT NOT NULL REFERENCES customers(id),
    reason          VARCHAR(32) NOT NULL,
    detail          VARCHAR(500),
    status          VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (response_id, customer_id),
    CONSTRAINT ck_demand_report_reason
        CHECK (reason IN ('SPAM','MISLEADING','INAPPROPRIATE','OTHER')),
    CONSTRAINT ck_demand_report_status
        CHECK (status IN ('OPEN','REVIEWED','DISMISSED','ACTIONED'))
);
CREATE INDEX IF NOT EXISTS idx_demand_report_status_time
    ON demand_response_reports (status, created_at DESC);

CREATE TABLE IF NOT EXISTS customer_demand_shop_blocks (
    customer_id     BIGINT NOT NULL REFERENCES customers(id),
    shop_id         BIGINT NOT NULL REFERENCES shops(id),
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (customer_id, shop_id)
);
CREATE INDEX IF NOT EXISTS idx_demand_shop_block_shop
    ON customer_demand_shop_blocks (shop_id, customer_id);

CREATE INDEX IF NOT EXISTS idx_demand_response_shop_time
    ON demand_responses (shop_id, created_at DESC);

CREATE TABLE IF NOT EXISTS demand_audit_events (
    id              BIGSERIAL PRIMARY KEY,
    request_id      BIGINT NOT NULL REFERENCES demand_requests(id),
    response_id     BIGINT REFERENCES demand_responses(id),
    actor_type      VARCHAR(24) NOT NULL,
    actor_id        BIGINT,
    shop_id         BIGINT REFERENCES shops(id),
    event_type      VARCHAR(32) NOT NULL,
    details         JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT ck_demand_audit_actor
        CHECK (actor_type IN ('CUSTOMER','MERCHANT','SYSTEM'))
);
CREATE INDEX IF NOT EXISTS idx_demand_audit_request_time
    ON demand_audit_events (request_id, created_at DESC, id DESC);
