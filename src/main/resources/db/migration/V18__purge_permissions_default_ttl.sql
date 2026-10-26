-- =====================================================================
-- V18__purge_permissions_default_ttl — purge `default_ttl_minutes` from
-- access.permissions.
--
-- Consent grants are perpetual until revoked: the window lives in the
-- host validity row (to_date_time = null, VALIDITY.md A3) and revocation
-- is an explicit act. The per-permission TTL was an unused second expiry
-- mechanism layered on top of the validity window (UPGRADE.txt). PENDING
-- request windows are unaffected — their TTL is a code constant
-- (AppGrantService.PENDING_REQUEST_TTL), not a column.
-- =====================================================================

ALTER TABLE access.permissions DROP COLUMN IF EXISTS default_ttl_minutes;
