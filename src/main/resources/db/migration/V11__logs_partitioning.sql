-- ═══════════════════════════════════════════════════════════════════════
-- V11 — audit logs become real partitions of notraius.logs.
--
-- Until now every month was an INDEPENDENT table (notraius.logs_YYYY_MM)
-- with no parent relation: every reader had to enumerate tables itself,
-- concatenate rows in Java, and re-implement COUNT/GROUP BY merges per
-- month — a hand-rolled approximation of partitioning that already broke
-- readers twice (they assumed a base notraius.logs existed and got
-- either errors or silent zeros).
--
-- This migration creates the real thing: notraius.logs as a PostgreSQL
-- declarative table PARTITION BY RANGE (created_at), attaches every
-- existing monthly table under it, and adds a DEFAULT partition as a
-- backstop. From here on every query is plain single-statement SQL against
-- notraius.logs — PostgreSQL routes inserts and prunes partitions by
-- created_at.
--
-- Notes:
--   * PRIMARY KEY widens from (id) to (id, created_at): PostgreSQL
--     requires the partition key in a partitioned table's unique
--     constraints. Ids are UUIDs, so cross-month uniqueness remains
--     practically guaranteed.
--   * Per-partition indexes are dropped and re-derived from parent-level
--     partitioned indexes (created at the end): one definition, inherited
--     by every partition — including ones created later by
--     LogPartitionRepository.
--   * logs_default must stay empty; a row landing there (clock skew,
--     backdated write) is a bug — the insert path uses now().
--   * Append-only — V1..V10 untouched.
-- ═══════════════════════════════════════════════════════════════════════

DO $$
DECLARE
    r      RECORD;
    suffix TEXT;
    from_b TEXT;
    to_b   TEXT;
BEGIN
    IF to_regclass('notraius.logs') IS NOT NULL THEN
        RETURN; -- already partitioned
    END IF;

    EXECUTE '
        CREATE TABLE notraius.logs (
            id           UUID NOT NULL DEFAULT gen_random_uuid(),
            created_at   timestamptz NOT NULL DEFAULT now(),
            updated_at   timestamptz NOT NULL DEFAULT now(),
            entity_id    UUID,
            reference_id UUID,
            device_id    UUID,
            old_data     JSONB,
            new_data     JSONB,
            actor        VARCHAR(20) NOT NULL,
            created_by   UUID        NOT NULL,
            activity_id  UUID,
            operation    VARCHAR(100),
            session_id   UUID
        ) PARTITION BY RANGE (created_at)';

    FOR r IN
        SELECT tablename FROM pg_tables
        WHERE schemaname = 'notraius' AND tablename LIKE 'logs\_%'
        ORDER BY tablename
    LOOP
        suffix := substring(r.tablename FROM 'logs_(\d{4}_\d{2})$');
        IF suffix IS NULL THEN
            CONTINUE; -- not a monthly table (e.g. logs_default)
        END IF;
        from_b := substr(suffix, 1, 4) || '-' || substr(suffix, 6, 2) || '-01 00:00:00+00';
        -- timestamp (not timestamptz) arithmetic: to_char on a timestamptz
        -- would render in the session timezone and skew the bound.
        to_b := to_char((from_b::timestamp + INTERVAL '1 month'), 'YYYY-MM-DD HH24:MI:SS') || '+00';

        -- The id-only PK cannot satisfy the partitioned PK (id, created_at);
        -- the parent's ADD PRIMARY KEY below rebuilds a matching index.
        EXECUTE format('ALTER TABLE notraius.%I DROP CONSTRAINT IF EXISTS %I',
                       r.tablename, r.tablename || '_pkey');

        -- Old per-month indexes are re-derived from the parent's partitioned
        -- indexes after ATTACH — drop them here so nothing is duplicated.
        EXECUTE format('DROP INDEX IF EXISTS notraius.%I', 'idx_' || r.tablename || '_entity_id');
        EXECUTE format('DROP INDEX IF EXISTS notraius.%I', 'idx_' || r.tablename || '_reference_id');
        EXECUTE format('DROP INDEX IF EXISTS notraius.%I', 'idx_' || r.tablename || '_device_id');
        EXECUTE format('DROP INDEX IF EXISTS notraius.%I', 'idx_' || r.tablename || '_actor');
        EXECUTE format('DROP INDEX IF EXISTS notraius.%I', 'idx_' || r.tablename || '_created_by');
        EXECUTE format('DROP INDEX IF EXISTS notraius.%I', 'idx_' || r.tablename || '_activity_id');
        EXECUTE format('DROP INDEX IF EXISTS notraius.%I', 'idx_' || r.tablename || '_session_id');
        EXECUTE format('DROP INDEX IF EXISTS notraius.%I', 'idx_' || r.tablename || '_created_at');

        EXECUTE format('ALTER TABLE notraius.logs ATTACH PARTITION notraius.%I '
                       || 'FOR VALUES FROM (%L) TO (%L)',
                       r.tablename, from_b, to_b);
    END LOOP;

    -- Backstop so an out-of-range created_at can never fail an audit insert.
    EXECUTE 'CREATE TABLE notraius.logs_default PARTITION OF notraius.logs DEFAULT';

    -- Parent-level unique constraint (rebuilt per partition) + one index
    -- definition inherited by every partition, present and future.
    EXECUTE 'ALTER TABLE notraius.logs ADD PRIMARY KEY (id, created_at)';
    EXECUTE 'CREATE INDEX idx_logs_entity_id    ON notraius.logs (entity_id)';
    EXECUTE 'CREATE INDEX idx_logs_reference_id ON notraius.logs (reference_id)';
    EXECUTE 'CREATE INDEX idx_logs_device_id    ON notraius.logs (device_id)';
    EXECUTE 'CREATE INDEX idx_logs_actor        ON notraius.logs (actor)';
    EXECUTE 'CREATE INDEX idx_logs_created_by   ON notraius.logs (created_by)';
    EXECUTE 'CREATE INDEX idx_logs_activity_id  ON notraius.logs (activity_id)';
    EXECUTE 'CREATE INDEX idx_logs_session_id   ON notraius.logs (session_id)';
    EXECUTE 'CREATE INDEX idx_logs_created_at   ON notraius.logs (created_at)';
END $$;
