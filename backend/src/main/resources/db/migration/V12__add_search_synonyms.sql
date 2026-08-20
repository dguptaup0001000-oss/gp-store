-- Hindi/Hinglish grocery vocabulary for Smart Search.
--
-- WHY THIS TABLE HAS TO EXIST. Every other layer of Smart Search is
-- algorithmic: SearchNormalizer's phonetic key already collapses spelling
-- variants and typos (chawal/chaval/chaawal, sogar/sugar, haldi/haldhi) with
-- no data at all. But no string algorithm gets from "chini" to "sugar" -
-- that is a TRANSLATION. It needs a dictionary.
--
-- WHAT KEEPS IT SMALL. One row per CONCEPT, not per spelling. The phonetic
-- key generates the variants: "chini" is stored once and "cheeni", "chinee"
-- and "chiny" all reach it. That is why this is ~100 rows rather than the
-- thousands of hand-written variants the design deliberately avoids.
--
-- It is also DATA, not code: a shop that sells something regional adds a row
-- and Smart Search picks it up on the next cache refresh, with no deploy.
--
-- THE VOCABULARY IS NOT SEEDED HERE. It lives in
-- src/main/resources/search-synonyms.csv and is loaded by
-- SynonymDictionary.seedIfEmpty on first startup. Seeding from this migration
-- instead would tie the feature to migrations having been run: CI runs with
-- Flyway disabled and the schema built from JPA entities, so the table would
-- exist and be empty there, and search would silently lose every Hindi word
-- with nothing failing to say so. Seeding from the application covers every
-- environment, including a fresh developer database.
CREATE TABLE IF NOT EXISTS search_synonyms (
    id             BIGSERIAL PRIMARY KEY,
    -- What a customer types. Stored as written so the table stays readable
    -- and editable by a person; the phonetic key is derived in Java at load
    -- time rather than stored, so a change to the phonetic rules does not
    -- need a data migration.
    term           VARCHAR(64)  NOT NULL,
    -- What to search the catalogue for instead. Must be a word that actually
    -- appears in product names or brands.
    canonical_term VARCHAR(64)  NOT NULL,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_search_synonym_term UNIQUE (term)
);

CREATE INDEX IF NOT EXISTS idx_search_synonyms_active ON search_synonyms (active);
