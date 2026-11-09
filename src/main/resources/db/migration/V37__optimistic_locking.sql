-- ============================================================================
-- V37 — the concurrency token the aggregates were missing
-- ============================================================================
-- Every aggregate already carries `version`, but that column is a CONTENT
-- revision: "every accepted edit is a new truth whose number joined the row"
-- (V28). It is hand-incremented by the services (`setVersion(getVersion()+1)`)
-- and it proves nothing about concurrency — two requests that read v1 both
-- write v2, and the loser's edit disappears without a trace. Nothing in the
-- schema, the entities or the services could detect that.
--
-- This migration adds the token that can: `lock_version`, owned by Hibernate
-- (`@Version`). It is a different number with a different meaning —
-- the domain revision stays visible to clients, the lock token stays
-- internal. On every UPDATE Hibernate adds `AND lock_version = <loaded>`;
-- when the row moved underneath the caller the write affects no row and the
-- flush raises OptimisticLockException, which the shared
-- GlobalExceptionHandler already answers as 409 CONFLICT with a retry
-- message. That handler existed for a collision no entity could produce
-- until now.
--
-- Why these three tables: they are the aggregates whose rows are the truth of
-- money and legal standing (a listing's terms, a tenancy, a booked visit).
-- Their children that move balances (lease deposits, breakdowns) already take
-- row locks explicitly; these three rely on read-check-write alone.
--
-- `DEFAULT 0` fills every existing row, so the token participates from the
-- first write with no backfill scan. The CHECK keeps it non-negative: a
-- counter that can go backwards is not a version.
-- ============================================================================

ALTER TABLE property.properties
    ADD COLUMN lock_version integer DEFAULT 0 NOT NULL;

ALTER TABLE property.properties
    ADD CONSTRAINT ck_properties_lock_version_non_negative CHECK (lock_version >= 0);

ALTER TABLE lease.leases
    ADD COLUMN lock_version integer DEFAULT 0 NOT NULL;

ALTER TABLE lease.leases
    ADD CONSTRAINT ck_leases_lock_version_non_negative CHECK (lock_version >= 0);

ALTER TABLE visit.visits
    ADD COLUMN lock_version integer DEFAULT 0 NOT NULL;

ALTER TABLE visit.visits
    ADD CONSTRAINT ck_visits_lock_version_non_negative CHECK (lock_version >= 0);
