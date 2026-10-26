-- =============================================================================
-- V14__purge_agent_role — the AGENT tier is gone on this host.
-- =============================================================================
-- Three consequences of deleting the AGENT role, all applied here:
--
--   1. AGENT permission policies are dropped. PermissionSeeder only ever
--      INSERTs (it never reconciles deletions), so the policies written while
--      the annotations still said 'AGENT' would otherwise linger forever as
--      rows that no caller can match. The seeder re-creates the same
--      (activity, operation) policies for ADMIN on the next boot.
--
--   2. security.settings.platform_role loses its 'AGENT' vocabulary. What
--      remains is exactly two values:
--        ADMIN  — the platform's single administrator; the partial unique
--                 index uk_settings_one_active_admin keeps it singular.
--        MEMBER — a plain membership carrying NO authority of its own (it
--                 resolves to no authority code, so it yields level -1).
--      The column DEFAULT becomes MEMBER so an implicitly created membership
--      can never mint an administrator — the old DEFAULT was 'AGENT'.
--
--   3. Existing AGENT memberships become MEMBER. Their holders keep the
--      platform affiliation (and its audit trail) but lose every staff
--      authority: platform capability now flows exclusively from SEAT
--      assignments owned by the platform's ADMIN.
-- =============================================================================

-- 1. Drop the orphaned AGENT policies.
DELETE FROM access.permissions WHERE authority_code = 'AGENT';

-- Orphaned policies of the purged platform-assignment API. The
-- `POST/DELETE /security/preferences/settings/platforms/{platformCode}`
-- routes are gone (the admin is bootstrapped once, never assigned), so no
-- annotation re-seeds their policies — they would survive as rows nobody can
-- ever match. PLATFORM.GET stays: the read routes still exist.
DELETE FROM access.permissions
 WHERE activity_code = 'PLATFORM' AND operation IN ('ASSIGN', 'REMOVE');

-- 2. The membership vocabulary: MEMBER by default, never ADMIN implicitly.
ALTER TABLE security.settings
    ALTER COLUMN platform_role SET DEFAULT 'MEMBER';

-- 3. Retire the former AGENT memberships.
UPDATE security.settings
   SET platform_role = 'MEMBER',
       updated_at = NOW()
 WHERE platform_role = 'AGENT';
