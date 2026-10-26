-- ═══════════════════════════════════════════════════════════════════════════
-- V4__event_action_engine.sql — NOTRAIUS: event scheduler tables + seeds (EAE)
-- ═══════════════════════════════════════════════════════════════════════════
-- Turns the event module from a CRUD registry into a working scheduler:
--   • event.steps       — the ordered chain of an event (operation + params)
--   • event.executions  — the execution ledger (one row per step-chain
--                         attempt per occurrence, unique (step_id, cycle_key))
--   • event.events      — +next_run_at (nullable; NULL = never due),
--                         -"values" (moved to event.steps.params)
--   • event.types       -schema (schema now derives from the operation registry)
-- event.events is asserted EMPTY before the change — manual backfill required
-- otherwise (existing rows would need their values split into steps).
-- Seeds: EVENTS/COMPLETED terminal status + EVENT_EXECUTIONS entity with
-- PENDING/RUNNING/SUCCEEDED/FAILED claim lifecycle.
-- ═══════════════════════════════════════════════════════════════════════════

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM event.events) THEN
        RAISE EXCEPTION 'event.events not empty — manual backfill of steps required';
    END IF;
END $$;

CREATE SCHEMA IF NOT EXISTS event;

-- ── Steps: the ordered chain of an event ──────────────────────────────────
CREATE TABLE event.steps (
    id          uuid DEFAULT gen_random_uuid() PRIMARY KEY NOT NULL,
    created_at  timestamp with time zone DEFAULT now() NOT NULL,
    updated_at  timestamp with time zone DEFAULT now() NOT NULL,
    event_id    uuid NOT NULL REFERENCES event.events(id) ON DELETE CASCADE,
    operation   character varying(64) NOT NULL,
    schema      jsonb NOT NULL,
    params      jsonb NOT NULL DEFAULT '{}'::jsonb,
    sequence    integer NOT NULL,
    CONSTRAINT uc_event_steps_event_seq UNIQUE (event_id, sequence)
);

CREATE INDEX idx_event_steps_event_id ON event.steps (event_id);

-- ── Executions: the ledger ────────────────────────────────────────────────
CREATE TABLE event.executions (
    id          uuid DEFAULT gen_random_uuid() PRIMARY KEY NOT NULL,
    created_at  timestamp with time zone DEFAULT now() NOT NULL,
    updated_at  timestamp with time zone DEFAULT now() NOT NULL,
    event_id    uuid NOT NULL REFERENCES event.events(id) ON DELETE CASCADE,
    step_id     uuid NOT NULL REFERENCES event.steps(id) ON DELETE CASCADE,
    validity_id uuid NOT NULL,
    cycle_key   timestamp with time zone NOT NULL,
    attempts    integer NOT NULL DEFAULT 0,
    started_at  timestamp with time zone,
    finished_at timestamp with time zone,
    output      jsonb,
    error       text
);

CREATE INDEX idx_event_executions_created  ON event.executions (created_at);
CREATE INDEX idx_event_executions_validity ON event.executions (validity_id);
CREATE INDEX idx_event_executions_event    ON event.executions (event_id);

-- D6 (UPGRADE.md issue 4): PARTIAL arbiter — one UNFINISHED execution per
-- (step_id, cycle_key). A finished attempt frees the cycle so a retry can
-- claim it again after a terminal failure, while two concurrent claims of
-- the same live cycle are still arbitrated (exactly one insert wins).
CREATE UNIQUE INDEX uc_exec_step_cycle
    ON event.executions (step_id, cycle_key)
    WHERE finished_at IS NULL;

-- ── Event table changes ───────────────────────────────────────────────────
ALTER TABLE event.events ADD COLUMN next_run_at timestamp with time zone;
CREATE INDEX idx_events_next_run ON event.events (next_run_at);

-- Backfill from the event's own validity window start (notraius.validity):
UPDATE event.events e SET next_run_at =
    (SELECT v.from_date_time FROM notraius.validity v WHERE v.id = e.validity_id);

ALTER TABLE event.types DROP COLUMN schema;
ALTER TABLE event.events DROP COLUMN "values";

-- ── Seeds ─────────────────────────────────────────────────────────────────
-- created_at/updated_at have no DEFAULT on notraius.statuses/entities.
-- Terminal status for one-shot events, under the existing EVENTS entity.
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
SELECT gen_random_uuid(), now(), now(), 'COMPLETED', 'One-shot event fully executed', en.id
FROM notraius.entities en WHERE en.code = 'EVENTS';

-- New registry entity + execution claim lifecycle statuses.
INSERT INTO notraius.entities (id, created_at, updated_at, code)
SELECT gen_random_uuid(), now(), now(), 'EVENT_EXECUTIONS';
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
SELECT gen_random_uuid(), now(), now(), s.code, s.description, ent.id
FROM notraius.entities ent
JOIN (VALUES
    ('PENDING',   'Claimed, not yet started'),
    ('RUNNING',   'Step chain executing'),
    ('SUCCEEDED', 'Chain completed for this cycle'),
    ('FAILED',    'Chain failed; event parked')
) AS s(code, description) ON true
WHERE ent.code = 'EVENT_EXECUTIONS';
