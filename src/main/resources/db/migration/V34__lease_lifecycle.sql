-- ═══════════════════════════════════════════════════════════════════════════
-- V34__lease_lifecycle.sql — Part 6.2–6.8: the life the agreement stage opened.
-- ═══════════════════════════════════════════════════════════════════════════
-- V33 landed the tables. This migration lands the columns the rest of Part 6
-- reads, each one named where it stands:
--
--   • lease.leases gains `origin` — NATIVE or IMPORT (6.6). An imported lease
--     carries no move-in conditions by nature (the platform-born documents
--     cannot exist for a tenancy that started on paper), and the tenant's
--     wholesale accept/refuse acts on it; the origin is what tells the
--     activation stage to skip the condition stage rather than wait forever.
--   • lease.breakdowns gains `settled_at` (when the row's bill was answered),
--     `assessment_filed` (6.3: one installment, one auto-assessment — a second
--     sweep pass must never judge the same miss twice) and `period_index` (the
--     calendar's own ordering, so a re-derived schedule and the FIFO walk agree
--     on which period a row bills without parsing timestamps).
--   • lease.notices gains `responded_at` — the response word says what the
--     tenant chose; the instant says when, so the 72-hour silence rule and an
--     explicit refusal stay distinguishable in the logs forever.
--   • lease.topups is NEW: every funding act that crosses the boundary carries
--     its OWN reference (6.1's funding law, restated at 6.3) — never the lease's,
--     because pay's (reference, currency) dedupe would hand back the live birth
--     funding. Persisting the row before the call leaves is what makes a retried
--     top-up find its funding instead of charging the tenant twice. The deposit
--     it reconstitutes and the units it restores are on the row.
--
-- Two vocabulary additions: the notice validity's own statuses (the notice
-- holds a validity, and that row is its clock — CLARIFICATION), and CANCELLED on
-- breakdowns for a period a reeled horizon cuts away before money ever stood
-- against it (6.5's truncation law).
-- ═══════════════════════════════════════════════════════════════════════════

-- ── 1. Vocabularies ────────────────────────────────────────────────────────

INSERT INTO notraius.entities (id, created_at, updated_at, code)
SELECT gen_random_uuid(), now(), now(), 'LEASE_TOPUPS'
WHERE NOT EXISTS (SELECT 1 FROM notraius.entities e WHERE e.code = 'LEASE_TOPUPS');

INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
SELECT gen_random_uuid(), now(), now(), v.code, v.description, e.id
FROM notraius.entities e
JOIN (VALUES
        ('ISSUED',    'Handed out; waiting on the tenant''s word or its silence'),
        ('ACCEPTED',  'The tenant accepted; the grace window is running'),
        ('REFUSED',   'The tenant refused; the machine escalates'),
        ('ESCALATED', 'Silence or expired grace; the VACATION notice followed'),
        ('CANCELLED', 'The landlord withdrew the notice; the prior truth was restored'),
        ('SATISFIED', 'The tenant rebuilt the cushion; the notice closed itself'),
        ('EXPIRED',   'The notice''s own clock ran out')
     ) AS v(code, description) ON TRUE
WHERE e.code = 'LEASE_NOTICES'
  AND NOT EXISTS (SELECT 1 FROM notraius.statuses s
                  WHERE s.entity_id = e.id AND s.code = v.code);

INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
SELECT gen_random_uuid(), now(), now(), 'CANCELLED',
       'Cut away by a reeled horizon before money ever stood against it', e.id
FROM notraius.entities e
WHERE e.code = 'LEASE_BREAKDOWNS'
  AND NOT EXISTS (SELECT 1 FROM notraius.statuses s
                  WHERE s.entity_id = e.id AND s.code = 'CANCELLED');

INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
SELECT gen_random_uuid(), now(), now(), v.code, v.description, e.id
FROM notraius.entities e
JOIN (VALUES
        ('PENDING', 'Created before the funding call left; its outcome may be unknown'),
        ('FUNDED',  'The top-up payment stands and its splits are in the ledger'),
        ('FAILED',  'The funding was definitively refused; nothing moved')
     ) AS v(code, description) ON TRUE
WHERE e.code = 'LEASE_TOPUPS'
  AND NOT EXISTS (SELECT 1 FROM notraius.statuses s
                  WHERE s.entity_id = e.id AND s.code = v.code);

-- ── 2. Lifecycle columns ───────────────────────────────────────────────────

ALTER TABLE lease.leases
    ADD COLUMN origin varchar(20) NOT NULL DEFAULT 'NATIVE';

ALTER TABLE lease.leases
    ADD CONSTRAINT ck_leases_origin CHECK (origin IN ('NATIVE', 'IMPORT'));

-- The rent frozen at birth (6.1/6.2): "the frozen amounts are frozen here, in
-- the payment's actual money, and never re-derived". A pricing re-versioned
-- mid-life must not silently re-price a running tenancy's calendar.
ALTER TABLE lease.leases
    ADD COLUMN rent_amount numeric(15,2);

ALTER TABLE lease.leases
    ADD CONSTRAINT ck_leases_rent_amount_positive CHECK (rent_amount IS NULL OR rent_amount > 0);

-- The counter's standard at birth (6.5): the notice engine reads the frozen
-- standard of the lease's birth, never the live policy, so a mid-life edit can
-- never re-price a running tenancy's grace.
ALTER TABLE lease.deposits
    ADD COLUMN standard_balance numeric(15,2);

ALTER TABLE lease.breakdowns
    ADD COLUMN settled_at timestamptz,
    ADD COLUMN assessment_filed boolean NOT NULL DEFAULT false,
    ADD COLUMN period_index integer;

-- A breakdown with no period index (a repair's charge) is legal; a billed
-- period carries one, and the calendar never bills the same index twice.
CREATE UNIQUE INDEX uk_lease_breakdowns_lease_period
    ON lease.breakdowns (lease_id, period_index)
    WHERE period_index IS NOT NULL;

ALTER TABLE lease.notices
    ADD COLUMN responded_at timestamptz;

-- The settlement's two clocks (6.7): the landlord's move-out week and the
-- billed sides' fortnight. One column cannot honestly be both, so the row
-- carries each where it governs — conditions and repairs read the week, the
-- debts' deadlines and the OVERDUE verdict read the fortnight.
ALTER TABLE lease.settlements
    ADD COLUMN move_out_deadline timestamptz;

-- ── 3. lease.topups — one funding act, one reference ──────────────────────

CREATE TABLE lease.topups (
    id           uuid          DEFAULT gen_random_uuid() NOT NULL,
    created_at   timestamptz   DEFAULT now() NOT NULL,
    updated_at   timestamptz   DEFAULT now() NOT NULL,
    created_by   uuid,
    lease_id     uuid          NOT NULL,
    deposit_id   uuid          NOT NULL,
    amount       numeric(15,2) NOT NULL,
    units        integer       NOT NULL,
    payment_id   uuid,
    status_id    uuid          NOT NULL,
    version      integer       DEFAULT 1 NOT NULL,
    CONSTRAINT pk_lease_topups PRIMARY KEY (id),
    CONSTRAINT ck_lease_topups_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_lease_topups_units_positive CHECK (units > 0),
    CONSTRAINT ck_lease_topups_version_positive CHECK (version > 0),
    CONSTRAINT fk_lease_topups_on_lease FOREIGN KEY (lease_id) REFERENCES lease.leases (id),
    CONSTRAINT fk_lease_topups_on_deposit FOREIGN KEY (deposit_id) REFERENCES lease.deposits (id),
    CONSTRAINT fk_lease_topups_on_status FOREIGN KEY (status_id) REFERENCES notraius.statuses (id)
);

CREATE INDEX idx_lease_topups_lease_id ON lease.topups USING btree (lease_id);
CREATE INDEX idx_lease_topups_deposit_id ON lease.topups USING btree (deposit_id);
CREATE INDEX idx_lease_topups_status_id ON lease.topups USING btree (status_id);
