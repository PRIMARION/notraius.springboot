-- =====================================================================
-- V19 — preferences.language varchar → shared-catalog id reference
-- (UPGRADE.txt item 23).
--
-- The language catalog lives in the SHARED lookup database
-- (security.languages, created by the shared Flyway migration V9 and
-- populated by the shared LanguageSeeder from a public languages API on
-- application boot) — exactly like geo, authority and media reference
-- data. This host does NOT get its own copy of the table.
--
-- preferences.language_id is therefore a plain UUID pointing at the
-- shared row with NO cross-database foreign key — the same pattern
-- users.role_id uses for shared-database role UUIDs (see the
-- SharedReferenceValidator javadoc).
--
-- The old free-form varchar(10) is dropped outright: codes held there
-- cannot be backfilled across databases, so an existing dev choice
-- resets and is re-picked from the catalog.
-- =====================================================================

ALTER TABLE security.preferences ADD COLUMN language_id uuid;
ALTER TABLE security.preferences DROP COLUMN language;
