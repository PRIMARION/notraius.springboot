-- ═══════════════════════════════════════════════════════════════════════════
-- V7__webhook_executions.sql — NOTRAIUS: webhook delivery ledger (event mirror)
-- ═══════════════════════════════════════════════════════════════════════════
-- Reframes the #WH-RETRY outbox (was security.webhook_deliveries) as an
-- execution ledger, the webhook-side mirror of event.executions (V4):
--   • security.webhook_executions — one row per attempt chain of one event
--     envelope against one registration
--   • status lives on a per-row host validity under the WEBHOOK_EXECUTIONS
--     registry entity (no status column on the ledger); claim lifecycle
--     PENDING → RUNNING → SUCCEEDED / FAILED
--   • started_at set-once ownership CAS; finished_at closes the chain
--   • PARTIAL arbiter (webhook_id, cycle_key) WHERE finished_at IS NULL —
--     one live outbox row per registration per envelope occurrence
--   • webhook-specific on top of the ledger: event_type/payload (the v1
--     envelope re-posted verbatim) and the #WH-RETRY backoff cursor
--     (max_attempts/next_attempt_at) — the event engine parks one row per
--     cycle and lets the reaper decide instead
-- Seeds: WEBHOOK_EXECUTIONS entity + claim lifecycle statuses.
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE security.webhook_executions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    webhook_id uuid NOT NULL,
    validity_id uuid NOT NULL,
    cycle_key timestamp with time zone NOT NULL,
    event_type character varying(255) NOT NULL,
    payload jsonb NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    max_attempts integer NOT NULL DEFAULT 5,
    next_attempt_at timestamp with time zone NOT NULL,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    error text
);

ALTER TABLE ONLY security.webhook_executions
    ADD CONSTRAINT pk_webhook_executions PRIMARY KEY (id);

CREATE INDEX idx_webhook_executions_created  ON security.webhook_executions (created_at);
CREATE INDEX idx_webhook_executions_validity ON security.webhook_executions (validity_id);
CREATE INDEX idx_webhook_executions_webhook  ON security.webhook_executions (webhook_id);
CREATE INDEX idx_webhook_executions_due      ON security.webhook_executions (next_attempt_at);

-- D6 mirror: PARTIAL arbiter — one UNFINISHED execution per
-- (webhook_id, cycle_key). A closed chain frees the cycle for a
-- re-dispatch, while two concurrent outbox writes of the same live
-- occurrence are still arbitrated (exactly one insert wins).
CREATE UNIQUE INDEX uc_whexec_webhook_cycle
    ON security.webhook_executions (webhook_id, cycle_key)
    WHERE finished_at IS NULL;

-- ── Seeds ─────────────────────────────────────────────────────────────────
-- created_at/updated_at have no DEFAULT on notraius.statuses/entities.
-- Registry entity + claim lifecycle statuses (EVENT_EXECUTIONS mirror);
-- entity-scoped so host expiry sweeps for other entities never touch
-- delivery rows.
INSERT INTO notraius.entities (id, created_at, updated_at, code)
SELECT gen_random_uuid(), now(), now(), 'WEBHOOK_EXECUTIONS';
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
SELECT gen_random_uuid(), now(), now(), s.code, s.description, ent.id
FROM notraius.entities ent
JOIN (VALUES
    ('PENDING',   'Claimed, awaiting delivery attempt'),
    ('RUNNING',   'HTTP attempt in flight'),
    ('SUCCEEDED', 'Envelope delivered to the receiver'),
    ('FAILED',    'Attempt chain exhausted; parked in the DLQ')
) AS s(code, description) ON true
WHERE ent.code = 'WEBHOOK_EXECUTIONS';
