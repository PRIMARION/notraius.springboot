-- =====================================================================
-- V15__purge_apps_deleted — purge the boolean `deleted` flag from app.apps.
--
-- Lifecycle is status-driven via status_id (ACTIVE / SUSPENDED / DELETED,
-- resolved against this host's own status registry). The boolean flag is a
-- pre-status leftover: no entity maps it, no query reads it, and the shared
-- database already dropped its copy (#CONS-01 / shared V4). Dropping it here
-- makes this host match that design.
-- =====================================================================

DROP INDEX IF EXISTS idx_apps_deleted;
ALTER TABLE app.apps DROP COLUMN IF EXISTS deleted;
