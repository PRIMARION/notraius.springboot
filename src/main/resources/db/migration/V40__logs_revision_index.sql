-- V40 — the revision read's index.
--
-- GET /{id}?version=N reconstructs a past truth from the audit chain:
--   WHERE entity_id = ? AND reference_id = ?  ORDER BY created_at DESC, id DESC
-- The parent has single-column indexes on entity_id and reference_id (V11) and
-- one on created_at; the planner could only ever use one of them, so a record
-- with a long history sorted every one of its entries before taking the newest.
-- This composite covers both predicates and the order in one scan.
--
-- Created on the partitioned parent: PostgreSQL propagates a parent index to
-- every existing partition (and attaches future ones automatically), so the
-- read is pruned to the partitions its clock can reach and is then an index
-- range scan inside each.
CREATE INDEX IF NOT EXISTS idx_logs_entity_reference_created
    ON notraius.logs (entity_id, reference_id, created_at DESC, id DESC);
