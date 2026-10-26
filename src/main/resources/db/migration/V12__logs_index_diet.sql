-- ═══════════════════════════════════════════════════════════════════════
-- V12 — audit-log index diet: eight single-column indexes become five
-- composites that match the queries that actually run.
--
-- V11 derived eight single-column indexes per partition. Since then every
-- real query has been audited, and the shape is always "equality column(s)
-- + created_at range/order":
--
--   * entity_id = ? ORDER BY created_at DESC        (listByEntity)
--   * reference_id = ? ORDER BY created_at DESC     (findByReferenceId)
--   * activity_id = ? ORDER BY created_at DESC      (findByActivityId)
--   * created_by = ? [AND created_at window]        (actor trail, /me)
--   * created_by = ? AND activity_id = ? + window   (subscription metering)
--   * actor = ? ORDER BY created_at DESC            (staff search filter)
--
-- A single-column index answers the equality but hands the planner an
-- unsorted pile; the composite serves filter AND order in one scan.
-- device_id / session_id are never filtered on (columns kept for reads) —
-- those two indexes are pure write tax and are dropped outright.
--
-- Net: 9 -> 7 index objects per partition (PK + created_at + 5 composites),
-- and the hot paths stop sorting.
--
-- Index names drop the schema prefix (same convention as V11: PostgreSQL
-- keeps the owning schema internally, and partitioned-index children are
-- auto-named). Append-only — V1..V11 untouched.
-- ═══════════════════════════════════════════════════════════════════════

-- Never-filtered-on columns: write tax with no reader.
DROP INDEX IF EXISTS notraius.idx_logs_device_id;
DROP INDEX IF EXISTS notraius.idx_logs_session_id;

-- Replaced by the composites below (leftmost-prefix serves the old shape).
DROP INDEX IF EXISTS notraius.idx_logs_entity_id;
DROP INDEX IF EXISTS notraius.idx_logs_reference_id;
DROP INDEX IF EXISTS notraius.idx_logs_activity_id;
DROP INDEX IF EXISTS notraius.idx_logs_created_by;
DROP INDEX IF EXISTS notraius.idx_logs_actor;

-- created_at stays: unfiltered date-range scans (staff search with only
-- from/to) still need it, and ORDER BY created_at DESC LIMIT pages ride it.
-- CREATE INDEX on the parent cascades to every attached partition AND to
-- partitions LogPartitionService creates later (new partitions inherit the
-- parent's index layout).
CREATE INDEX IF NOT EXISTS idx_logs_entity_id_created   ON notraius.logs (entity_id,   created_at);
CREATE INDEX IF NOT EXISTS idx_logs_reference_id_created ON notraius.logs (reference_id, created_at);
CREATE INDEX IF NOT EXISTS idx_logs_activity_id_created ON notraius.logs (activity_id, created_at);
CREATE INDEX IF NOT EXISTS idx_logs_created_by_created  ON notraius.logs (created_by,  created_at);
CREATE INDEX IF NOT EXISTS idx_logs_actor_created       ON notraius.logs (actor,       created_at);
