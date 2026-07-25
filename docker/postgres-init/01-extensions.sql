-- Runs automatically the FIRST time the postgres container starts with an
-- empty data volume. Only the extension goes here - the products table
-- doesn't exist yet at this point (Postgres starts before the Spring Boot
-- app has run Hibernate's schema creation), so the GIN indexes on products
-- have to be created AFTER the app has started at least once. See the note
-- in docker-compose.yml for the one-time follow-up command.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
