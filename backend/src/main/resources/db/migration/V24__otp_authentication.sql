-- OTP purpose-scoped challenges and short-lived password-reset tokens.
--
-- CI's empty-database job boots Hibernate ddl-auto=update BEFORE Flyway, so
-- these columns/tables may already exist from the entities. Production
-- validate boots Flyway first. ADD COLUMN / CREATE TABLE IF NOT EXISTS is both.
--
-- otp_hash remains a hash or the sentinel PROVIDER_MANAGED — never a plaintext OTP.

ALTER TABLE otp_verifications ADD COLUMN IF NOT EXISTS purpose VARCHAR(32);
UPDATE otp_verifications SET purpose = 'LOGIN' WHERE purpose IS NULL;
ALTER TABLE otp_verifications ALTER COLUMN purpose SET DEFAULT 'LOGIN';
ALTER TABLE otp_verifications ALTER COLUMN purpose SET NOT NULL;

ALTER TABLE otp_verifications ADD COLUMN IF NOT EXISTS consumed_at TIMESTAMP;
ALTER TABLE otp_verifications ADD COLUMN IF NOT EXISTS provider_reference VARCHAR(128);
ALTER TABLE otp_verifications ADD COLUMN IF NOT EXISTS resend_count INTEGER;
UPDATE otp_verifications SET resend_count = 0 WHERE resend_count IS NULL;
ALTER TABLE otp_verifications ALTER COLUMN resend_count SET DEFAULT 0;
ALTER TABLE otp_verifications ALTER COLUMN resend_count SET NOT NULL;

ALTER TABLE otp_verifications ADD COLUMN IF NOT EXISTS last_sent_at TIMESTAMP;
UPDATE otp_verifications SET last_sent_at = created_at WHERE last_sent_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_otp_verifications_mobile_purpose_created
    ON otp_verifications (mobile_number, purpose, created_at DESC);

CREATE TABLE IF NOT EXISTS password_reset_tokens (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES customers (id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    consumed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_customer_id
    ON password_reset_tokens (customer_id);
