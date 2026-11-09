-- V41 — the revision number as a column, so a revision read is a seek.
--
-- V40 indexed (entity_id, reference_id, created_at DESC, id DESC): it covered
-- the predicates and the order of the chain read, but not the revision itself,
-- so `?version=N` still walked every entry of that record and evaluated a CASE
-- over its JSON to find the one it wanted. On a record edited for years that is
-- a scan of its whole history for a single row.
--
-- The revision is therefore materialised, by the database, from the payload the
-- writer already stores: new_data states the version a write created, old_data
-- the version it replaced. GENERATED ... STORED is used rather than a column the
-- writer fills, for one reason: two writers cannot disagree. The expression is
-- the same guard the read used to carry inline (jsonb_typeof rejects a non-object
-- payload such as a batch id array; the regex rejects a non-numeric or absurd
-- version; only then is the cast to int safe), so a malformed audit row yields
-- NULL here instead of aborting a later read.
--
-- The indexes are PARTIAL (WHERE ... IS NOT NULL): the overwhelming majority of
-- audit rows are not versioned at all — every reference-catalogue write, every
-- login, every attachment — and there is no reason to carry them in an index
-- that only ever answers "which revision was this". PostgreSQL propagates both
-- the columns and the indexes to every partition of the parent, and attaches
-- future partitions with them.
--
-- idx_logs_entity_reference_created (V40) is dropped in the same migration: it
-- existed only to serve the version-filtered chain read this replaces, and the
-- two partial indexes below answer that read exactly. Keeping it would leave a
-- third btree maintained on every audit insert for no reader.
ALTER TABLE notraius.logs
    ADD COLUMN IF NOT EXISTS new_version int GENERATED ALWAYS AS (
        CASE WHEN jsonb_typeof(new_data) = 'object'
              AND new_data->>'version' ~ '^[0-9]{1,9}$'
             THEN (new_data->>'version')::int
        END) STORED;

ALTER TABLE notraius.logs
    ADD COLUMN IF NOT EXISTS old_version int GENERATED ALWAYS AS (
        CASE WHEN jsonb_typeof(old_data) = 'object'
              AND old_data->>'version' ~ '^[0-9]{1,9}$'
             THEN (old_data->>'version')::int
        END) STORED;

CREATE INDEX IF NOT EXISTS idx_logs_entity_reference_new_version
    ON notraius.logs (entity_id, reference_id, new_version, created_at DESC, id DESC)
    WHERE new_version IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_logs_entity_reference_old_version
    ON notraius.logs (entity_id, reference_id, old_version, created_at DESC, id DESC)
    WHERE old_version IS NOT NULL;

DROP INDEX IF EXISTS notraius.idx_logs_entity_reference_created;
