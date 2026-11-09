-- ═══════════════════════════════════════════════════════════════════════════
-- V33__lease_agreement.sql — Part 6: the lease schema and its agreement stage.
-- ═══════════════════════════════════════════════════════════════════════════
-- DATABASE.txt §5 declares the seven lease tables. This migration lands them
-- with the deltas PLAN Part 6 argues for, each one named where it stands:
--
--   • lease.leases gains `owner_id` — the beneficiary snapshot. The tenant
--     (`user_id`) pays and is refunded; the owner takes custody's releases and
--     the born payment's splits name them. Freezing it on the row makes the
--     three identities of PLAN 6.9 readable without joining pricing → property,
--     and survives a deed change mid-life (6.4: born custody never follows the
--     deed).
--   • lease.deposits gains `policy_id` — the standard the counter answers.
--     `balance` is the COUNTER in the policy's unit (months of advance, or the
--     month-equivalents an AMOUNT hull covers), never a price: PLAN 6.4 keeps
--     money in exactly one place, the born payment's escrows.
--   • lease.deposit_splits is NEW, and it is the atomicity law's ledger. 6.0
--     rules that one split maps to one unit of the policy's thing and has
--     exactly two fates — fully released or fully refunded, never drawn down.
--     FIFO consumption over "the still-HELD advance escrows" needs those
--     escrows to be rows with their own state, so each born split is copied
--     here with pay's payment/split identity and a status that is held,
--     released or refunded.
--   • lease.breakdowns gains `lease_id`, `deadline`, the period it bills, and
--     the escrow identity a DEPOSITED row stores (6.3). A deadline is not
--     optional: OVERDUE is the clock's verdict on it, and the vigil reads the
--     same date rollover everywhere.
--   • lease.conditions gains `lease_id` on the repair side as well, plus
--     `notes`, so the enumerated state of the premises is a row rather than a
--     convention; documents ride the storage module's (entity, reference) pair.
--   • lease.settlements gains `status_id` and the confirmation stamps: 6.7
--     makes an unpaid settlement OVERDUE and its confirmation the act that
--     completes the lease. One settlement per lease — `uk_settlements_lease`.
--   • lease.notices gets its OWN primary key (DATABASE.txt's `id UUID FK ...
--     PK` is the correction this plan makes: a lease spawns a parade of
--     notices), a `response` field so 72-hour silence is distinguishable from
--     an explicit refusal, and `previous_validity_to_date` so a VACATION
--     stamp's reverse can restore exactly what it overwrote — null included.
--     It holds an `expires_at`? No: it holds a validity row, and that row is
--     its clock (CLARIFICATION).
--
-- Statuses are seeder concerns (6.0): the words land here with the tables.
-- ═══════════════════════════════════════════════════════════════════════════

CREATE SCHEMA IF NOT EXISTS lease;

-- ── 1. Vocabularies ────────────────────────────────────────────────────────

INSERT INTO notraius.entities (id, created_at, updated_at, code)
SELECT gen_random_uuid(), now(), now(), v.code
FROM (VALUES
        ('LEASES'),
        ('LEASE_DEPOSITS'),
        ('LEASE_DEPOSIT_SPLITS'),
        ('LEASE_BREAKDOWNS'),
        ('LEASE_CONDITIONS'),
        ('LEASE_REPAIRS'),
        ('LEASE_SETTLEMENTS'),
        ('LEASE_NOTICES')
     ) AS v(code)
WHERE NOT EXISTS (SELECT 1 FROM notraius.entities e WHERE e.code = v.code);

-- The lease's own life: PENDING is born unfunded-looking intent; CONFIRMED is
-- the owner's commitment, awaiting the move-in conditions; ACTIVE is the
-- tenancy the calendar bills; COMPLETED ends at the settlement's confirmation;
-- CANCELLED is the pre-commitment word only (6.0); EXPIRED is silence's exit;
-- DISPUTED freezes every forced action while humans adjudicate (6.7).
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
SELECT gen_random_uuid(), now(), now(), v.code, v.description, e.id
FROM notraius.entities e
JOIN (VALUES
        ('PENDING',   'Funded intent: neither party is ready yet'),
        ('CONFIRMED', 'The owner committed; the move-in conditions stage is due'),
        ('ACTIVE',    'The tenancy lives; the calendar bills it'),
        ('COMPLETED', 'The settlement was confirmed; the lease is closed'),
        ('CANCELLED', 'The pre-commitment exit: the whole matrix went back'),
        ('EXPIRED',   'Silence ended it; the matrix was refunded in full'),
        ('DISPUTED',  'A ticket froze the machine; only humans move it now')
     ) AS v(code, description) ON TRUE
WHERE e.code = 'LEASES'
  AND NOT EXISTS (SELECT 1 FROM notraius.statuses s
                  WHERE s.entity_id = e.id AND s.code = v.code);

-- A deposit row's status is the counter's story, not the money's: the money is
-- in the splits. HELD = the standard stands; RELEASED = consumed as rent;
-- RETURNED = refunded at settlement.
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
SELECT gen_random_uuid(), now(), now(), v.code, v.description, e.id
FROM notraius.entities e
JOIN (VALUES
        ('HELD',     'The deposit stands as custody against the policy standard'),
        ('RELEASED', 'Consumed: the advance paid the rent it was parked for'),
        ('RETURNED', 'Refunded to the tenant at settlement')
     ) AS v(code, description) ON TRUE
WHERE e.code = 'LEASE_DEPOSITS'
  AND NOT EXISTS (SELECT 1 FROM notraius.statuses s
                  WHERE s.entity_id = e.id AND s.code = v.code);

-- The split's two fates and the state before either (PLAN 6.0 atomicity).
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
SELECT gen_random_uuid(), now(), now(), v.code, v.description, e.id
FROM notraius.entities e
JOIN (VALUES
        ('HELD',     'The escrow stands over this split; nothing has moved'),
        ('RELEASED', 'Released whole to the beneficiary its birth named'),
        ('REFUNDED', 'Returned whole to the funding wallet')
     ) AS v(code, description) ON TRUE
WHERE e.code = 'LEASE_DEPOSIT_SPLITS'
  AND NOT EXISTS (SELECT 1 FROM notraius.statuses s
                  WHERE s.entity_id = e.id AND s.code = v.code);

INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
SELECT gen_random_uuid(), now(), now(), v.code, v.description, e.id
FROM notraius.entities e
JOIN (VALUES
        ('PENDING',   'Owed and not yet answered'),
        ('SETTLED',   'Paid by a payment of its own (or a recorded cash fact)'),
        ('DEPOSITED', 'Satisfied from custody: the month''s advance split released'),
        ('OVERDUE',   'The deadline passed and nothing satisfied it')
     ) AS v(code, description) ON TRUE
WHERE e.code = 'LEASE_BREAKDOWNS'
  AND NOT EXISTS (SELECT 1 FROM notraius.statuses s
                  WHERE s.entity_id = e.id AND s.code = v.code);

INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
SELECT gen_random_uuid(), now(), now(), v.code, v.description, e.id
FROM notraius.entities e
JOIN (VALUES
        ('PENDING',   'Reported by its author and awaiting the other side''s word'),
        ('CONFIRMED', 'Counter-signed: both parties agree on these premises')
     ) AS v(code, description) ON TRUE
WHERE e.code IN ('LEASE_CONDITIONS', 'LEASE_REPAIRS')
  AND NOT EXISTS (SELECT 1 FROM notraius.statuses s
                  WHERE s.entity_id = e.id AND s.code = v.code);

-- A settlement is born OPEN (its two-week clocks run from its deadline), turns
-- OVERDUE when a billed side fails to meet it, and only CONFIRMED closes the
-- lease (6.7).
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
SELECT gen_random_uuid(), now(), now(), v.code, v.description, e.id
FROM notraius.entities e
JOIN (VALUES
        ('OPEN',      'Born with the end of the tenancy; the clocks run'),
        ('OVERDUE',   'A billed side missed its fortnight; the dispute door opens'),
        ('CONFIRMED', 'The money walk is finished and the lease is COMPLETED')
     ) AS v(code, description) ON TRUE
WHERE e.code = 'LEASE_SETTLEMENTS'
  AND NOT EXISTS (SELECT 1 FROM notraius.statuses s
                  WHERE s.entity_id = e.id AND s.code = v.code);

-- The property's two lease-born states (6.0): LEASED is occupancy; DISABLED is
-- the deliberate dark a vacated place keeps until its owner re-lists it.
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
SELECT gen_random_uuid(), now(), now(), v.code, v.description, e.id
FROM notraius.entities e
JOIN (VALUES
        ('LEASED',   'A live tenancy occupies it: no viewings, no new leases'),
        ('DISABLED', 'Vacated and deliberately dark until the owner makes it available')
     ) AS v(code, description) ON TRUE
WHERE e.code = 'PROPERTY'
  AND NOT EXISTS (SELECT 1 FROM notraius.statuses s
                  WHERE s.entity_id = e.id AND s.code = v.code);

-- Types: deposits are typed by the standard they answer; breakdowns by what
-- kind of money row they are; conditions by the stage of the tenancy; notices
-- by the engine that issued them.
INSERT INTO notraius.types (id, created_at, updated_at, entity_id, type_id, code, label, description, status_id)
SELECT gen_random_uuid(), now(), now(), e.id, NULL, v.code, v.label, v.description, t.id
FROM notraius.entities e
JOIN (VALUES
        ('ADVANCE',     'Advance',     'Rent paid ahead: a prepaid installment standard'),
        ('SECURITY',    'Security',    'Collateral held for the tenancy''s whole life'),
        ('ELECTRICITY', 'Electricity', 'Utility deposit held alongside the security')
     ) AS v(code, label, description) ON TRUE
JOIN notraius.entities te ON te.code = 'TYPES'
JOIN notraius.statuses t ON t.entity_id = te.id AND t.code = 'ACTIVE'
WHERE e.code = 'LEASE_DEPOSITS'
  AND NOT EXISTS (SELECT 1 FROM notraius.types ty
                  WHERE ty.entity_id = e.id AND ty.code = v.code);

INSERT INTO notraius.types (id, created_at, updated_at, entity_id, type_id, code, label, description, status_id)
SELECT gen_random_uuid(), now(), now(), e.id, NULL, v.code, v.label, v.description, t.id
FROM notraius.entities e
JOIN (VALUES
        ('INSTALLMENT', 'Installment', 'Rent and whatever else the cadence bills'),
        ('SETTLEMENT',  'Settlement',  'The end-of-life money walk''s own rows'),
        ('REPAIR',      'Repair',      'A repair charged through the lease')
     ) AS v(code, label, description) ON TRUE
JOIN notraius.entities te ON te.code = 'TYPES'
JOIN notraius.statuses t ON t.entity_id = te.id AND t.code = 'ACTIVE'
WHERE e.code = 'LEASE_BREAKDOWNS'
  AND NOT EXISTS (SELECT 1 FROM notraius.types ty
                  WHERE ty.entity_id = e.id AND ty.code = v.code);

INSERT INTO notraius.types (id, created_at, updated_at, entity_id, type_id, code, label, description, status_id)
SELECT gen_random_uuid(), now(), now(), e.id, NULL, v.code, v.label, v.description, t.id
FROM notraius.entities e
JOIN (VALUES
        ('BEFORE_MOVE_IN', 'Before move-in', 'The premises as received'),
        ('AFTER_MOVE_OUT', 'After move-out', 'The premises as returned')
     ) AS v(code, label, description) ON TRUE
JOIN notraius.entities te ON te.code = 'TYPES'
JOIN notraius.statuses t ON t.entity_id = te.id AND t.code = 'ACTIVE'
WHERE e.code = 'LEASE_CONDITIONS'
  AND NOT EXISTS (SELECT 1 FROM notraius.types ty
                  WHERE ty.entity_id = e.id AND ty.code = v.code);

INSERT INTO notraius.types (id, created_at, updated_at, entity_id, type_id, code, label, description, status_id)
SELECT gen_random_uuid(), now(), now(), e.id, NULL, v.code, v.label, v.description, t.id
FROM notraius.entities e
JOIN (VALUES
        ('DEPOSIT',      'Deposit notice',      'The cushion is critically thin: reconstitute the advance'),
        ('VACATION',     'Vacation notice',     'Three months to vacate; the validity is stamped'),
        ('RECONVERSION', 'Reconversion notice', 'The imported lease''s debt-clearance clock'),
        ('LEASE_END',    'Lease end',           'The notice that ends a tenancy'),
        ('EVICTION',     'Eviction',            'The notice that removes an occupant')
     ) AS v(code, label, description) ON TRUE
JOIN notraius.entities te ON te.code = 'TYPES'
JOIN notraius.statuses t ON t.entity_id = te.id AND t.code = 'ACTIVE'
WHERE e.code = 'LEASE_NOTICES'
  AND NOT EXISTS (SELECT 1 FROM notraius.types ty
                  WHERE ty.entity_id = e.id AND ty.code = v.code);

-- ── 2. lease.leases ────────────────────────────────────────────────────────

CREATE TABLE lease.leases (
    id           uuid          DEFAULT gen_random_uuid() NOT NULL,
    created_at   timestamptz   DEFAULT now() NOT NULL,
    updated_at   timestamptz   DEFAULT now() NOT NULL,
    created_by   uuid,
    user_id      uuid          NOT NULL,
    owner_id     uuid          NOT NULL,
    pricing_id   uuid          NOT NULL,
    terms_id     uuid,
    validity_id  uuid          NOT NULL,
    status_id    uuid          NOT NULL,
    version      integer       DEFAULT 1 NOT NULL,
    CONSTRAINT pk_leases PRIMARY KEY (id),
    CONSTRAINT ck_leases_version_positive CHECK (version > 0),
    CONSTRAINT fk_leases_on_pricing FOREIGN KEY (pricing_id) REFERENCES property.pricings (id),
    CONSTRAINT fk_leases_on_validity FOREIGN KEY (validity_id) REFERENCES notraius.validity (id),
    CONSTRAINT fk_leases_on_status FOREIGN KEY (status_id) REFERENCES notraius.statuses (id)
);

CREATE INDEX idx_leases_user_id ON lease.leases USING btree (user_id);
CREATE INDEX idx_leases_owner_id ON lease.leases USING btree (owner_id);
CREATE INDEX idx_leases_pricing_id ON lease.leases USING btree (pricing_id);
CREATE INDEX idx_leases_status_id ON lease.leases USING btree (status_id);
CREATE INDEX idx_leases_validity_id ON lease.leases USING btree (validity_id);
-- The staleness sweep's worklist and the owner's page both read this pair.
CREATE INDEX idx_leases_status_created_at ON lease.leases USING btree (status_id, created_at);

-- ── 3. lease.deposits ──────────────────────────────────────────────────────

CREATE TABLE lease.deposits (
    id           uuid          DEFAULT gen_random_uuid() NOT NULL,
    created_at   timestamptz   DEFAULT now() NOT NULL,
    updated_at   timestamptz   DEFAULT now() NOT NULL,
    lease_id     uuid          NOT NULL,
    policy_id    uuid          NOT NULL,
    type_id      uuid          NOT NULL,
    balance      numeric(15,2) DEFAULT 0 NOT NULL,
    status_id    uuid          NOT NULL,
    version      integer       DEFAULT 1 NOT NULL,
    CONSTRAINT pk_lease_deposits PRIMARY KEY (id),
    CONSTRAINT ck_lease_deposits_balance_nonnegative CHECK (balance >= 0),
    CONSTRAINT ck_lease_deposits_version_positive CHECK (version > 0),
    CONSTRAINT uk_lease_deposits_lease_policy UNIQUE (lease_id, policy_id),
    CONSTRAINT fk_lease_deposits_on_lease FOREIGN KEY (lease_id) REFERENCES lease.leases (id),
    CONSTRAINT fk_lease_deposits_on_policy FOREIGN KEY (policy_id) REFERENCES notraius.policies (id),
    CONSTRAINT fk_lease_deposits_on_type FOREIGN KEY (type_id) REFERENCES notraius.types (id),
    CONSTRAINT fk_lease_deposits_on_status FOREIGN KEY (status_id) REFERENCES notraius.statuses (id)
);

CREATE INDEX idx_lease_deposits_lease_id ON lease.deposits USING btree (lease_id);
CREATE INDEX idx_lease_deposits_status_id ON lease.deposits USING btree (status_id);

-- ── 4. lease.deposit_splits — the atomicity ledger ─────────────────────────

CREATE TABLE lease.deposit_splits (
    id           uuid          DEFAULT gen_random_uuid() NOT NULL,
    created_at   timestamptz   DEFAULT now() NOT NULL,
    updated_at   timestamptz   DEFAULT now() NOT NULL,
    lease_id     uuid          NOT NULL,
    deposit_id   uuid          NOT NULL,
    payment_id   uuid          NOT NULL,
    split_id     uuid          NOT NULL,
    reference_id uuid          NOT NULL,
    amount       numeric(15,2) NOT NULL,
    sequence     integer       NOT NULL,
    status_id    uuid          NOT NULL,
    CONSTRAINT pk_lease_deposit_splits PRIMARY KEY (id),
    CONSTRAINT ck_lease_deposit_splits_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_lease_deposit_splits_sequence_positive CHECK (sequence > 0),
    CONSTRAINT uk_lease_deposit_splits_pay_split UNIQUE (payment_id, split_id),
    CONSTRAINT fk_lease_deposit_splits_on_lease FOREIGN KEY (lease_id) REFERENCES lease.leases (id),
    CONSTRAINT fk_lease_deposit_splits_on_deposit FOREIGN KEY (deposit_id) REFERENCES lease.deposits (id),
    CONSTRAINT fk_lease_deposit_splits_on_status FOREIGN KEY (status_id) REFERENCES notraius.statuses (id)
);

CREATE INDEX idx_lease_deposit_splits_lease_id ON lease.deposit_splits USING btree (lease_id);
CREATE INDEX idx_lease_deposit_splits_deposit_id ON lease.deposit_splits USING btree (deposit_id);
-- The consumption walk: oldest HELD split first, per lease.
CREATE INDEX idx_lease_deposit_splits_status_sequence
    ON lease.deposit_splits USING btree (lease_id, status_id, sequence);

-- ── 5. lease.breakdowns — the calendar's rows ──────────────────────────────

CREATE TABLE lease.breakdowns (
    id                uuid          DEFAULT gen_random_uuid() NOT NULL,
    created_at        timestamptz   DEFAULT now() NOT NULL,
    updated_at        timestamptz   DEFAULT now() NOT NULL,
    created_by        uuid,
    entity_id         uuid          NOT NULL,
    reference_id      uuid          NOT NULL,
    lease_id          uuid          NOT NULL,
    title             varchar(255),
    description       text,
    amount            numeric(15,2) NOT NULL,
    type_id           uuid          NOT NULL,
    status_id         uuid          NOT NULL,
    user_id           uuid,
    deadline          timestamptz   NOT NULL,
    period_start      timestamptz,
    period_end        timestamptz,
    escrow_payment_id uuid,
    escrow_split_id   uuid,
    version           integer       DEFAULT 1 NOT NULL,
    CONSTRAINT pk_lease_breakdowns PRIMARY KEY (id),
    CONSTRAINT ck_lease_breakdowns_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_lease_breakdowns_version_positive CHECK (version > 0),
    CONSTRAINT fk_lease_breakdowns_on_entity FOREIGN KEY (entity_id) REFERENCES notraius.entities (id),
    CONSTRAINT fk_lease_breakdowns_on_lease FOREIGN KEY (lease_id) REFERENCES lease.leases (id),
    CONSTRAINT fk_lease_breakdowns_on_type FOREIGN KEY (type_id) REFERENCES notraius.types (id),
    CONSTRAINT fk_lease_breakdowns_on_status FOREIGN KEY (status_id) REFERENCES notraius.statuses (id)
);

CREATE INDEX idx_lease_breakdowns_lease_id ON lease.breakdowns USING btree (lease_id);
CREATE INDEX idx_lease_breakdowns_reference ON lease.breakdowns USING btree (entity_id, reference_id);
CREATE INDEX idx_lease_breakdowns_status_id ON lease.breakdowns USING btree (status_id);
-- The vigil's read: what is due, oldest deadline first.
CREATE INDEX idx_lease_breakdowns_status_deadline ON lease.breakdowns USING btree (status_id, deadline);

-- ── 6. lease.conditions + lease.repairs ────────────────────────────────────

CREATE TABLE lease.conditions (
    id           uuid          DEFAULT gen_random_uuid() NOT NULL,
    created_at   timestamptz   DEFAULT now() NOT NULL,
    updated_at   timestamptz   DEFAULT now() NOT NULL,
    created_by   uuid,
    lease_id     uuid          NOT NULL,
    type_id      uuid          NOT NULL,
    status_id    uuid          NOT NULL,
    notes        text,
    version      integer       DEFAULT 1 NOT NULL,
    CONSTRAINT pk_lease_conditions PRIMARY KEY (id),
    CONSTRAINT ck_lease_conditions_version_positive CHECK (version > 0),
    CONSTRAINT fk_lease_conditions_on_lease FOREIGN KEY (lease_id) REFERENCES lease.leases (id),
    CONSTRAINT fk_lease_conditions_on_type FOREIGN KEY (type_id) REFERENCES notraius.types (id),
    CONSTRAINT fk_lease_conditions_on_status FOREIGN KEY (status_id) REFERENCES notraius.statuses (id)
);

CREATE INDEX idx_lease_conditions_lease_id ON lease.conditions USING btree (lease_id);
CREATE INDEX idx_lease_conditions_status_id ON lease.conditions USING btree (status_id);

CREATE TABLE lease.repairs (
    id           uuid          DEFAULT gen_random_uuid() NOT NULL,
    created_at   timestamptz   DEFAULT now() NOT NULL,
    updated_at   timestamptz   DEFAULT now() NOT NULL,
    created_by   uuid,
    condition_id uuid          NOT NULL,
    lease_id     uuid          NOT NULL,
    title        varchar(255)  NOT NULL,
    description  text,
    status_id    uuid          NOT NULL,
    version      integer       DEFAULT 1 NOT NULL,
    CONSTRAINT pk_lease_repairs PRIMARY KEY (id),
    CONSTRAINT ck_lease_repairs_version_positive CHECK (version > 0),
    CONSTRAINT fk_lease_repairs_on_condition FOREIGN KEY (condition_id) REFERENCES lease.conditions (id),
    CONSTRAINT fk_lease_repairs_on_lease FOREIGN KEY (lease_id) REFERENCES lease.leases (id),
    CONSTRAINT fk_lease_repairs_on_status FOREIGN KEY (status_id) REFERENCES notraius.statuses (id)
);

CREATE INDEX idx_lease_repairs_condition_id ON lease.repairs USING btree (condition_id);
CREATE INDEX idx_lease_repairs_lease_id ON lease.repairs USING btree (lease_id);

-- ── 7. lease.settlements ───────────────────────────────────────────────────

CREATE TABLE lease.settlements (
    id           uuid          DEFAULT gen_random_uuid() NOT NULL,
    created_at   timestamptz   DEFAULT now() NOT NULL,
    updated_at   timestamptz   DEFAULT now() NOT NULL,
    created_by   uuid,
    lease_id     uuid          NOT NULL,
    status_id    uuid          NOT NULL,
    deadline     timestamptz   NOT NULL,
    confirmed_at timestamptz,
    confirmed_by uuid,
    version      integer       DEFAULT 1 NOT NULL,
    CONSTRAINT pk_lease_settlements PRIMARY KEY (id),
    CONSTRAINT ck_lease_settlements_version_positive CHECK (version > 0),
    CONSTRAINT uk_lease_settlements_lease UNIQUE (lease_id),
    CONSTRAINT fk_lease_settlements_on_lease FOREIGN KEY (lease_id) REFERENCES lease.leases (id),
    CONSTRAINT fk_lease_settlements_on_status FOREIGN KEY (status_id) REFERENCES notraius.statuses (id)
);

CREATE INDEX idx_lease_settlements_status_deadline
    ON lease.settlements USING btree (status_id, deadline);

-- ── 8. lease.notices ───────────────────────────────────────────────────────

CREATE TABLE lease.notices (
    id                        uuid         DEFAULT gen_random_uuid() NOT NULL,
    created_at                timestamptz  DEFAULT now() NOT NULL,
    updated_at                timestamptz  DEFAULT now() NOT NULL,
    created_by                uuid,
    lease_id                  uuid         NOT NULL,
    type_id                   uuid         NOT NULL,
    reason                    text,
    response                  varchar(20),
    validity_id               uuid         NOT NULL,
    previous_validity_to_date timestamptz,
    version                   integer      DEFAULT 1 NOT NULL,
    CONSTRAINT pk_lease_notices PRIMARY KEY (id),
    CONSTRAINT ck_lease_notices_version_positive CHECK (version > 0),
    CONSTRAINT fk_lease_notices_on_lease FOREIGN KEY (lease_id) REFERENCES lease.leases (id),
    CONSTRAINT fk_lease_notices_on_type FOREIGN KEY (type_id) REFERENCES notraius.types (id),
    CONSTRAINT fk_lease_notices_on_validity FOREIGN KEY (validity_id) REFERENCES notraius.validity (id)
);

CREATE INDEX idx_lease_notices_lease_id ON lease.notices USING btree (lease_id);
CREATE INDEX idx_lease_notices_type_id ON lease.notices USING btree (type_id);
