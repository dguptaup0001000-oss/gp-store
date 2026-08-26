-- Heartbeats from the production backup sidecar. The application never
-- writes this table; backend/docker/backup/backup.sh inserts one row
-- per run. OpsStatusController reads it so an administrator can see whether
-- backups are actually happening, not whether a script exists in git.

CREATE TABLE ops_backup_runs (
    id          BIGSERIAL PRIMARY KEY,
    taken_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    filename    VARCHAR(255) NOT NULL,
    bytes       BIGINT,
    sha256      VARCHAR(64),
    status      VARCHAR(32) NOT NULL,
    detail      VARCHAR(1000)
);

CREATE INDEX idx_ops_backup_runs_taken_at ON ops_backup_runs (taken_at DESC);

COMMENT ON TABLE ops_backup_runs IS
    'One row per Postgres backup attempt. Written by the backup sidecar, read by /api/admin/ops/backups.';
