-- =========================================================================
--  Drop ALL Linked-Store tables, sequences, indexes, and types from the
--  current Postgres database.  Companion to `schema.sql` — run this FIRST
--  when you want to wipe the Render / local DB and start 100% from scratch.
--
--  Safe to re-run: uses IF EXISTS on every target.  CASCADE on each DROP
--  TABLE takes care of FK references, FK constraints, and child indexes.
--  Any lingering user-defined ENUM types are dropped at the end.
-- =========================================================================

SET client_min_messages = WARNING;

-- -------------------------------------------------------------------------
--  1. Tables (reverse dependency order — deepest children first, parents
--     last — so CASCADE has minimal work to do, and we avoid accidental
--     "cannot drop table X because other objects depend on it" spurious
--     errors on older Postgres builds.)
-- -------------------------------------------------------------------------
DROP TABLE IF EXISTS returned_inspection_records CASCADE;
DROP TABLE IF EXISTS refunds                      CASCADE;
DROP TABLE IF EXISTS transaction_items            CASCADE;
DROP TABLE IF EXISTS transactions                 CASCADE;
DROP TABLE IF EXISTS inventory_locks              CASCADE;
DROP TABLE IF EXISTS qr_tokens                    CASCADE;
DROP TABLE IF EXISTS store_invites                CASCADE;
DROP TABLE IF EXISTS subscription_plan_features   CASCADE;
DROP TABLE IF EXISTS subscriptions                CASCADE;
DROP TABLE IF EXISTS subscription_plans           CASCADE;
DROP TABLE IF EXISTS product_variants             CASCADE;
DROP TABLE IF EXISTS products                     CASCADE;
DROP TABLE IF EXISTS store_users                  CASCADE;
DROP TABLE IF EXISTS stores                       CASCADE;

-- -------------------------------------------------------------------------
--  2. Extra safety — any leftover Flyway/Liquibase changelog tables
--     (we don't ship with these, but cleanup anyway.)
-- -------------------------------------------------------------------------
DROP TABLE IF EXISTS flyway_schema_history        CASCADE;
DROP TABLE IF EXISTS databasechangelog            CASCADE;
DROP TABLE IF EXISTS databasechangeloglock        CASCADE;

-- -------------------------------------------------------------------------
--  3. User-defined ENUM types (Postgres native, not the plain VARCHAR
--     CHECK constraints we use today — kept for future-proofing if we
--     switch to native PG ENUMs.)
-- -------------------------------------------------------------------------
DROP TYPE IF EXISTS user_role_enum          CASCADE;
DROP TYPE IF EXISTS user_status_enum        CASCADE;
DROP TYPE IF EXISTS subscription_status_enum CASCADE;
DROP TYPE IF EXISTS product_status_enum     CASCADE;
DROP TYPE IF EXISTS inventory_lock_status_enum CASCADE;
DROP TYPE IF EXISTS transaction_status_enum CASCADE;
DROP TYPE IF EXISTS refund_status_enum      CASCADE;
DROP TYPE IF EXISTS rir_status_enum         CASCADE;
DROP TYPE IF EXISTS subscription_provider_enum CASCADE;
DROP TYPE IF EXISTS plan_feature_flag_enum  CASCADE;
DROP TYPE IF EXISTS invite_status_enum      CASCADE;
