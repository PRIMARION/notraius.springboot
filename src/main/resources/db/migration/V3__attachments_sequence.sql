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
-- registry); live rows are those not carrying the DELETED status. Postgres
-- does not allow subqueries in index predicates, so the seeded
-- ATTACHMENTS/DELETED UUID (V2 seeders) is inlined — same value the host's
-- AttachmentStatusResolver resolves at runtime.
CREATE UNIQUE INDEX uq_attachments_live_sequence
    ON notraius.attachments (entity_id, reference_id, sequence)
    WHERE sequence IS NOT NULL
      AND status_id IS DISTINCT FROM 'e48ab746-224f-4176-b896-b635fe20cb46'::uuid;
