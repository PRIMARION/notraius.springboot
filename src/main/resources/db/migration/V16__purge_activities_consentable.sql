-- =====================================================================
-- V16__purge_activities_consentable — purge the boolean `consentable`
-- flag from access.activities.
--
-- The permission table is the delegation catalog: a consent grants a
-- permission, so an activity+operation nobody can permission is not
-- delegatable (ConsentGuard fails closed on empty permission rows). The
-- per-family flag was a second, config-fed copy of that decision
-- (app.activities.consentable + boot rebase) and is purged platform-wide
-- (UPGRADE.txt).
-- =====================================================================

ALTER TABLE access.activities DROP COLUMN IF EXISTS consentable;
