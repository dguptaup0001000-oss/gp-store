-- Shared foundation for search intelligence, "I Need This", and reviewed AI catalogue drafts.
-- Raw customer signals have a bounded retention window; merchant-facing reads aggregate them.

CREATE TABLE IF NOT EXISTS marketplace_search_events (
    id                  BIGSERIAL PRIMARY KEY,
    customer_id         BIGINT,
    query_text          VARCHAR(240) NOT NULL,
    normalized_query    VARCHAR(240) NOT NULL,
    category_id         BIGINT REFERENCES categories(id),
    commerce_mode       VARCHAR(24),
    latitude_cell       NUMERIC(5,2),
    longitude_cell      NUMERIC(5,2),
    result_count        INTEGER NOT NULL CHECK (result_count >= 0),
    created_at          TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_search_events_time_query
    ON marketplace_search_events (created_at DESC, normalized_query);
CREATE INDEX IF NOT EXISTS idx_search_events_zero_time
    ON marketplace_search_events (created_at DESC) WHERE result_count = 0;
CREATE INDEX IF NOT EXISTS idx_search_events_area_time
    ON marketplace_search_events (latitude_cell, longitude_cell, created_at DESC);

CREATE TABLE IF NOT EXISTS demand_requests (
    id                  BIGSERIAL PRIMARY KEY,
    customer_id         BIGINT NOT NULL REFERENCES customers(id),
    description         VARCHAR(500) NOT NULL,
    photo_url           VARCHAR(1000),
    quantity            INTEGER NOT NULL DEFAULT 1 CHECK (quantity > 0 AND quantity <= 10000),
    category_id         BIGINT REFERENCES categories(id),
    budget              NUMERIC(12,2) CHECK (budget IS NULL OR budget > 0),
    required_by         TIMESTAMP,
    latitude            DOUBLE PRECISION NOT NULL,
    longitude           DOUBLE PRECISION NOT NULL,
    radius_km           NUMERIC(7,2) NOT NULL CHECK (radius_km > 0),
    preferred_mode      VARCHAR(24),
    status              VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    expires_at          TIMESTAMP NOT NULL,
    closed_at           TIMESTAMP,
    created_at          TIMESTAMP NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT ck_demand_status CHECK (status IN ('OPEN','CLOSED','CANCELLED','EXPIRED'))
);
CREATE INDEX IF NOT EXISTS idx_demand_customer_time
    ON demand_requests (customer_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_demand_open_expiry
    ON demand_requests (expires_at, id) WHERE status = 'OPEN';

-- Materialized eligibility prevents a merchant from changing query parameters to scrape demand.
CREATE TABLE IF NOT EXISTS demand_request_recipients (
    request_id          BIGINT NOT NULL REFERENCES demand_requests(id),
    shop_id             BIGINT NOT NULL REFERENCES shops(id),
    notified_at         TIMESTAMP NOT NULL DEFAULT now(),
    viewed_at           TIMESTAMP,
    PRIMARY KEY (request_id, shop_id)
);
CREATE INDEX IF NOT EXISTS idx_demand_recipient_shop
    ON demand_request_recipients (shop_id, notified_at DESC, request_id);

CREATE TABLE IF NOT EXISTS demand_responses (
    id                  BIGSERIAL PRIMARY KEY,
    request_id          BIGINT NOT NULL REFERENCES demand_requests(id),
    shop_id             BIGINT NOT NULL REFERENCES shops(id),
    status              VARCHAR(24) NOT NULL,
    price               NUMERIC(12,2) CHECK (price IS NULL OR price > 0),
    quantity            INTEGER CHECK (quantity IS NULL OR quantity > 0),
    ready_minutes       INTEGER CHECK (ready_minutes IS NULL OR ready_minutes >= 0),
    commerce_mode       VARCHAR(24),
    merchant_note       VARCHAR(500),
    created_at          TIMESTAMP NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (request_id, shop_id),
    CONSTRAINT ck_demand_response_status CHECK (status IN ('AVAILABLE','NOT_AVAILABLE'))
);
CREATE INDEX IF NOT EXISTS idx_demand_response_request
    ON demand_responses (request_id, created_at DESC);

CREATE TABLE IF NOT EXISTS ai_extraction_jobs (
    id                  BIGSERIAL PRIMARY KEY,
    shop_id             BIGINT NOT NULL REFERENCES shops(id),
    requested_by        BIGINT NOT NULL REFERENCES customers(id),
    source_type         VARCHAR(32) NOT NULL,
    object_key          VARCHAR(1000),
    manual_text         VARCHAR(2000),
    status              VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    error_code          VARCHAR(80),
    created_at          TIMESTAMP NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP NOT NULL DEFAULT now(),
    completed_at        TIMESTAMP,
    CONSTRAINT ck_ai_job_status CHECK (status IN ('QUEUED','PROCESSING','REVIEW_READY','FAILED'))
);
CREATE INDEX IF NOT EXISTS idx_ai_jobs_shop_time
    ON ai_extraction_jobs (shop_id, created_at DESC);

CREATE TABLE IF NOT EXISTS ai_catalog_drafts (
    id                  BIGSERIAL PRIMARY KEY,
    job_id              BIGINT REFERENCES ai_extraction_jobs(id),
    shop_id             BIGINT NOT NULL REFERENCES shops(id),
    requested_by        BIGINT NOT NULL REFERENCES customers(id),
    name                VARCHAR(255),
    brand               VARCHAR(255),
    description         TEXT,
    category_id         BIGINT REFERENCES categories(id),
    variant_label       VARCHAR(120),
    quantity            DOUBLE PRECISION,
    unit                VARCHAR(40),
    mrp                 NUMERIC(12,2),
    selling_price       NUMERIC(12,2),
    stock               INTEGER,
    barcode             VARCHAR(120),
    image_url           VARCHAR(1000),
    commerce_mode       VARCHAR(24),
    confidence          NUMERIC(4,3),
    generated_fields    VARCHAR(1000) NOT NULL DEFAULT '',
    uncertain_fields    VARCHAR(1000) NOT NULL DEFAULT '',
    status              VARCHAR(24) NOT NULL DEFAULT 'REVIEW',
    approved_product_id BIGINT REFERENCES products(id),
    approved_by         BIGINT REFERENCES customers(id),
    approved_at         TIMESTAMP,
    created_at          TIMESTAMP NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT ck_ai_draft_status CHECK (status IN ('REVIEW','APPROVED','REJECTED')),
    CONSTRAINT ck_ai_draft_prices CHECK (
        (mrp IS NULL OR mrp >= 0) AND
        (selling_price IS NULL OR selling_price > 0) AND
        (mrp IS NULL OR selling_price IS NULL OR selling_price <= mrp)
    )
);
CREATE INDEX IF NOT EXISTS idx_ai_drafts_shop_status_time
    ON ai_catalog_drafts (shop_id, status, created_at DESC);
