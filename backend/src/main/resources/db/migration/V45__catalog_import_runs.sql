-- Bulk catalogue import: what was uploaded, what it did, and what went wrong.
--
-- IF NOT EXISTS throughout, like every migration here: Flyway runs AFTER
-- Hibernate's schema generation in this project (FlywayAfterSchemaConfig), so
-- these objects may already exist by the time this file runs.

-- One row per upload. Kept even when the import was only previewed and never
-- committed, because "somebody tried to import this and it was full of
-- errors" is exactly the history a shopkeeper needs when 400 prices are
-- suddenly wrong and nobody remembers who uploaded what.
CREATE TABLE IF NOT EXISTS catalog_import_runs (
    id                BIGSERIAL PRIMARY KEY,
    filename          VARCHAR(255) NOT NULL,
    admin_email       VARCHAR(255),

    -- IMPORT creates and updates; UPDATE_ONLY refuses to create anything, so
    -- a price sheet with a typo'd SKU fails loudly instead of quietly
    -- inventing a product nobody sells.
    mode              VARCHAR(16)  NOT NULL,

    -- PREVIEWED -> COMMITTED, or FAILED. A run that never leaves PREVIEWED
    -- changed nothing.
    status            VARCHAR(16)  NOT NULL,

    -- THE PREVIEW AND THE COMMIT MUST BE THE SAME FILE. The admin previews,
    -- reads the summary, then commits by uploading again; the server refuses
    -- if the bytes differ. Without this, "947 valid, 22 errors" describes one
    -- file and the commit applies another - the single most dangerous thing
    -- a bulk importer can do.
    -- VARCHAR, NOT CHAR(64), and the difference is not cosmetic. The entity
    -- maps a String, which Hibernate expects as varchar; CHAR comes back as
    -- bpchar and ddl-auto=validate refuses to start:
    --   "found [bpchar (Types#CHAR)], but expecting [varchar(64)]".
    -- The local bootstrap never caught it because ddl-auto=update creates the
    -- table from the ENTITY and leaves this CREATE TABLE IF NOT EXISTS a
    -- no-op - so the SQL below had never actually run anywhere until CI
    -- rehearsed it against a database that did not already have the table.
    file_sha256       VARCHAR(64)  NOT NULL,

    total_rows        INTEGER      NOT NULL DEFAULT 0,
    valid_rows        INTEGER      NOT NULL DEFAULT 0,
    warning_rows      INTEGER      NOT NULL DEFAULT 0,
    error_rows        INTEGER      NOT NULL DEFAULT 0,
    created_count     INTEGER      NOT NULL DEFAULT 0,
    updated_count     INTEGER      NOT NULL DEFAULT 0,

    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    committed_at      TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_catalog_import_runs_created
    ON catalog_import_runs (created_at DESC);

-- Every complaint, addressed to a row and a column so it can be fixed in the
-- spreadsheet rather than guessed at. A problem with no row number is a
-- problem nobody can act on.
CREATE TABLE IF NOT EXISTS catalog_import_problems (
    id           BIGSERIAL PRIMARY KEY,
    run_id       BIGINT       NOT NULL,
    row_number   INTEGER      NOT NULL,
    field        VARCHAR(64),
    severity     VARCHAR(16)  NOT NULL,
    problem      VARCHAR(500) NOT NULL,
    suggestion   VARCHAR(500)
);

CREATE INDEX IF NOT EXISTS ix_catalog_import_problems_run
    ON catalog_import_problems (run_id, row_number);
