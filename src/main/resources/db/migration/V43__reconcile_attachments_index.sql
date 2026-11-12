-- =====================================================================
-- Reconcile the attachments live-sequence index on databases that already
-- applied the pre-fix baseline.
--
-- V3 (and V1 for another index) originally inlined a seeded status UUID in
-- the index predicate. The seeders now generate ids with gen_random_uuid(),
-- so those files were corrected in place: fresh databases get the dynamic
-- version from V3. A database migrated before the correction keeps the old
-- index (whose predicate can never match) and will not re-run V3 — this
-- migration drops and recreates it, resolving the ATTACHMENTS/DELETED id
-- from the registry. Idempotent on fresh databases.
--
-- V8 moved the table out of the notraius schema (notraius.attachments ->
-- storage.attachments), so every reference below targets storage — the
-- statuses and entities registries, however, never moved.
--
-- =====================================================================

DROP INDEX IF EXISTS storage.uq_attachments_live_sequence;

DO $$
DECLARE deleted_status uuid;
BEGIN
    SELECT s.id INTO deleted_status
    FROM notraius.statuses s
    JOIN notraius.entities e ON e.id = s.entity_id
    WHERE e.code = 'ATTACHMENTS' AND s.code = 'DELETED';
    IF deleted_status IS NULL THEN
        RAISE EXCEPTION 'ATTACHMENTS/DELETED status is missing from notraius';
    END IF;
    EXECUTE format(
        'CREATE UNIQUE INDEX uq_attachments_live_sequence ON storage.attachments '
        '(entity_id, reference_id, sequence) '
        'WHERE sequence IS NOT NULL AND status_id IS DISTINCT FROM %L::uuid',
        deleted_status);
END $$;
