-- ─────────────────────────────────────────────────────────────────────────────
-- V3: Attachment sequence ordering
--
-- Adds a per-parent ordering column to notraius.attachments. Rows sharing the
-- same (entity_id, reference_id) learn an explicit position so callers can
-- order and re-order documents deterministically instead of relying on
-- created_at. Same change as the pay service's V3 — the attachments table
-- lives in every host database per the ecosystem ownership rule, and the
-- shared Attachment entity now maps the column everywhere.
--
-- Backfill takes the position from creation order (created_at, id tie-break);
-- the partial unique index covers live rows only, so a soft-deleted
-- attachment never blocks reuse of its slot.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE notraius.attachments
    ADD COLUMN sequence integer;

UPDATE notraius.attachments att
SET sequence = ranked.rn
FROM (
    SELECT id,
           row_number() OVER (PARTITION BY entity_id, reference_id
                              ORDER BY created_at ASC, id ASC) AS rn
    FROM notraius.attachments
    WHERE entity_id IS NOT NULL AND reference_id IS NOT NULL
) ranked
WHERE att.id = ranked.id;

-- Live-row uniqueness: one slot per parent. This host's lifecycle is
-- status-driven (ATTACHMENTS/ACTIVE and ATTACHMENTS/DELETED in the notraius
-- registry); live rows are those not carrying the DELETED status.
-- Postgres does not allow subqueries in index predicates, and status ids are
-- generated (V2 uses gen_random_uuid()), so the DELETED id is resolved here
-- and inlined through dynamic SQL — the same row AttachmentStatusResolver
-- resolves at runtime, without pinning a UUID in the migration.

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
        'CREATE UNIQUE INDEX uq_attachments_live_sequence ON notraius.attachments '
        '(entity_id, reference_id, sequence) '
        'WHERE sequence IS NOT NULL AND status_id IS DISTINCT FROM %L::uuid',
        deleted_status);
END $$;
