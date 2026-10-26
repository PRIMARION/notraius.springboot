-- V23__purge_settings.sql — NOTRAIUS: the per-host settings membership table
-- =============================================================================
-- UPGRADE.txt settings raze: the platform-admin registry lives in the SHARED
-- lookup database now (shared db/migrations V10 — one row per platform:
-- user_id + platform_code). The per-host security.settings membership table
-- and its registry entry are purged from this host database:
--   • authority is the global role ladder + seat delegations (the
--     RoleLookupService staff source is gone),
--   • the identity host's AdminUserJob writes the shared registry rows.
-- Order matters: the table goes first (its rows FK onto the SETTINGS
-- statuses), then the statuses, then the entity row.
-- =============================================================================

DROP TABLE IF EXISTS security.settings;

DELETE FROM notraius.statuses
WHERE entity_id = (SELECT id FROM notraius.entities WHERE code = 'SETTINGS');

DELETE FROM notraius.entities WHERE code = 'SETTINGS';
