-- =====================================================================
-- V17__purge_consents_created_by — purge the `created_by` uuid from
-- access.consents.
--
-- The column duplicated the actor vocabulary: user_id IS the grantor
-- (who granted), actor + actor_id IS the grantee (APP id or seat id —
-- chk_consents_actor forbids USER/SERVICE, and the enum cannot even
-- express them). No entity mapped the column, no code wrote it, no
-- query read it. Who created what and why is traceable in the audit
-- logs (logs.created_by + activity), not in a second, stale copy here
-- (UPGRADE.txt).
-- =====================================================================

ALTER TABLE access.consents DROP COLUMN IF EXISTS created_by;
