-- V22__permission_id_sweep.sql — NOTRAIUS: the pair becomes permission_id
-- ============================================================
-- UPGRADE.txt permission-id sweep: wherever a row carried an activity id plus
-- a SINGLE operation, the pair collapsed into permission_id — the permission
-- row (access.permissions) already stores activity_code + operation + body,
-- so the duplicated columns go. Three tables convert:
--
--   • subscription.plan_features.activity_id → permission_id
--       The OLD binding recorded NO operation (family-level only), so the
--       (activity, operation) pair cannot be carried faithfully — the plan/
--       feature catalog is operator-managed (the PlanFeatureBootstrapper was
--       purged) and bindings simply start empty. Old family rows also could
--       not say WHICH initiating operation they capped; the new model binds
--       one concrete permission row and the guard enforces per pair.
--   • webhook.webhooks (activity_id + operation) → permission_id
--       Registrations are backfilled onto the matching permission row of the
--       OLD pair (access.activities carries the old family code; the
--       permission rows were seeded by earlier boots).
--   • webhook.webhook_executions.event_type → permission_id
--       Delivery and retry RE-DERIVE the dotted ACTIVITY.OPERATION type from
--       the owning webhook's permission row. Execution rows inherit the
--       owning webhook's resolved permission (nullable — history stays
--       intact even where the mapping could not complete).
--
-- Dropping a column drops its indexes and single-column FKs with it.
-- ============================================================

-- ── subscription.plan_features ────────────────────────────────────────────
ALTER TABLE subscription.plan_features DROP COLUMN activity_id;
ALTER TABLE subscription.plan_features ADD COLUMN permission_id uuid;

ALTER TABLE subscription.plan_features
    ADD CONSTRAINT fk_plan_feature_permission FOREIGN KEY (permission_id)
    REFERENCES access.permissions (id);

CREATE INDEX idx_plan_feature_permission_id
    ON subscription.plan_features USING btree (permission_id);

-- ── webhook.webhooks ──────────────────────────────────────────────────────
ALTER TABLE webhook.webhooks ADD COLUMN permission_id uuid;

-- Backfill: (old activity family, operation) → the matching permission row.
UPDATE webhook.webhooks w
SET permission_id = (
    SELECT p.id
    FROM access.permissions p
    JOIN access.activities a ON a.id = w.activity_id
    WHERE p.activity_code = a.code
      AND p.operation = w.operation
    ORDER BY p.created_at
    LIMIT 1);

ALTER TABLE webhook.webhooks DROP COLUMN operation;
ALTER TABLE webhook.webhooks DROP COLUMN activity_id;

ALTER TABLE webhook.webhooks
    ADD CONSTRAINT fk_webhook_permission FOREIGN KEY (permission_id)
    REFERENCES access.permissions (id);

CREATE INDEX idx_webhooks_permission_id
    ON webhook.webhooks USING btree (permission_id);

-- ── webhook.webhook_executions ────────────────────────────────────────────
ALTER TABLE webhook.webhook_executions ADD COLUMN permission_id uuid;

-- Backfill: executions inherit the owning webhook's resolved target.
UPDATE webhook.webhook_executions x
SET permission_id = w.permission_id
FROM webhook.webhooks w
WHERE x.webhook_id = w.id
  AND w.permission_id IS NOT NULL
  AND x.permission_id IS NULL;

ALTER TABLE webhook.webhook_executions DROP COLUMN event_type;
