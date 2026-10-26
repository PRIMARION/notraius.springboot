-- =====================================================================
-- V26 — access.permissions: activity identity as the activity row id
-- (UPGRADE.txt #38).
--
-- activity_code varchar(100) duplicated the identity of the
-- access.activities row (SAME database), so it becomes activity_id uuid
-- FK -> access.activities. authority_code STAYS (the authority catalog
-- lives in the shared lookup database — cross-DB, the code is the only
-- runtime key there).
--
-- The dotted-code vocabulary on the wire is unchanged: PermissionCache /
-- guards keep matching by (code, operation); only the stored column moved
-- onto the row id (UPGRADE.txt #38).
--
-- Backfill resolves each dotted family code onto its activities row.
-- =====================================================================

ALTER TABLE access.permissions ADD COLUMN activity_id uuid;

UPDATE access.permissions p
SET activity_id = (SELECT a.id FROM access.activities a WHERE a.code = p.activity_code)
WHERE p.activity_id IS NULL;

ALTER TABLE access.permissions
    DROP CONSTRAINT IF EXISTS uk_permission_policy,
    DROP COLUMN IF EXISTS activity_code,
    ADD CONSTRAINT uk_permission_policy UNIQUE (activity_id, operation, authority_code, authority_access),
    ADD CONSTRAINT fk_permissions_activity FOREIGN KEY (activity_id) REFERENCES access.activities(id);

DROP INDEX IF EXISTS idx_perm_activity_op;
CREATE INDEX idx_perm_activity_op ON access.permissions USING btree (activity_id, operation);

-- The OLD rate-limit ledger (item 25's predecessor): replaced by
-- rate.limits + rate.ledger, no entity and no reader reference this table.
DROP TABLE IF EXISTS security.rate_limit;
