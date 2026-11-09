-- ═══════════════════════════════════════════════════════════════════════════
-- V25__reference_catalogue.sql — CONFIG/reference sub-module
-- ═══════════════════════════════════════════════════════════════════════════
-- PLAN.md Part 1 (types), Part 2 (durations), Part 3.3 (amenities) — the three
-- reference catalogues of the config module, served by ONE controller and ONE
-- service (the operator's grouping: types + durations + amenities are the
-- reference sub-module).
--
-- Schema notes (stated here because they are plan deltas on DATABASE.txt v4.0):
--   • `notraius.types` gains `status_id` — retirement is a STATE, never a
--     delete (Part 1: "a type in use cannot vanish … retired rather than
--     deleted"), and a catalogue row with no lifecycle column cannot express
--     that law.
--   • `notraius.durations` replaces the free-text `code` with `type_id`
--     (CLARIFICATION item 4 / Part 2): the unit vocabulary is admin-seeded
--     TYPES (SECOND, MINUTE, HOUR, DAY, MONTH) and the duration row says
--     "for THIS type, the actual value is N". It also gains `status_id` for
--     the same retire-not-delete reason.
--   • Every row carries `created_by` per DATABASE.txt's general rules.
--
-- Retro-compatibility: nothing here touches an existing table.
-- ═══════════════════════════════════════════════════════════════════════════


-- ── 1. Entity registry rows ────────────────────────────────────────────────
-- TYPES / DURATIONS / AMENITIES are the three catalogues' own status
-- namespaces; DURATION_UNIT namespaces the time-unit vocabulary the duration
-- rows bind to (Part 2: "SECOND, MINUTE, HOUR, DAY and MONTH are *type rows*
-- under their own entity binding — the entity is the namespace").

INSERT INTO notraius.entities (id, created_at, updated_at, code)
SELECT gen_random_uuid(), now(), now(), v.code
FROM (VALUES ('TYPES'), ('DURATIONS'), ('AMENITIES'), ('DURATION_UNIT')) AS v(code)
WHERE NOT EXISTS (SELECT 1 FROM notraius.entities e WHERE e.code = v.code);


-- ── 2. Status vocabularies ─────────────────────────────────────────────────
-- A catalogue row's status comes from ITS OWN entity namespace, so every
-- namespace that will hold rows needs its own vocabulary:
--   TYPES / DURATION_UNIT : ACTIVE, RETIRED   (type rows, the units included)
--   DURATIONS             : ACTIVE, RETIRED   (duration rows)
--   AMENITIES             : PENDING, ACTIVE, REJECTED, CANCELLED, DISABLED,
--                           RETIRED

INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
SELECT gen_random_uuid(), now(), now(), v.code, v.description, e.id
FROM notraius.entities e
JOIN (VALUES
        ('ACTIVE',   'Live reference row'),
        ('RETIRED',  'Retired reference row — resolves for history, never offered again')
     ) AS v(code, description) ON TRUE
-- DURATION_UNIT is included because section 6 seeds the canonical units as
-- type rows under it; without its own ACTIVE status those rows could not be
-- born (the seed joins this very vocabulary).
WHERE e.code IN ('TYPES', 'DURATION_UNIT')
  AND NOT EXISTS (SELECT 1 FROM notraius.statuses s
                  WHERE s.entity_id = e.id AND s.code = v.code);

INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
SELECT gen_random_uuid(), now(), now(), v.code, v.description, e.id
FROM notraius.entities e
JOIN (VALUES
        ('ACTIVE',  'Live duration row'),
        ('RETIRED', 'Retired duration row — resolves for history, never offered again')
     ) AS v(code, description) ON TRUE
WHERE e.code = 'DURATIONS'
  AND NOT EXISTS (SELECT 1 FROM notraius.statuses s
                  WHERE s.entity_id = e.id AND s.code = v.code);

INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
SELECT gen_random_uuid(), now(), now(), v.code, v.description, e.id
FROM notraius.entities e
JOIN (VALUES
        ('PENDING',   'Proposed by a user, awaiting an admin decision'),
        ('ACTIVE',    'Live in the catalogue; selectable by properties'),
        ('REJECTED',  'Proposal refused; never became selectable'),
        ('CANCELLED', 'Proposal withdrawn by its proposer before any decision'),
        ('DISABLED',  'Switched off by admin; holdings cascade-suppressed'),
        ('RETIRED',   'Permanently out of the catalogue')
     ) AS v(code, description) ON TRUE
WHERE e.code = 'AMENITIES'
  AND NOT EXISTS (SELECT 1 FROM notraius.statuses s
                  WHERE s.entity_id = e.id AND s.code = v.code);


-- ── 3. notraius.types — hierarchical, entity-bound catalogue ───────────────
-- `type_id` is the SELF parent link (DATABASE.txt 1.2's column name). A null
-- parent means a root. The entity binding is the namespace: a child's entity
-- must equal its parent's (enforced in the service — a CHECK cannot read the
-- parent row, and a trigger for a catalogue rule is the heavier hammer).

CREATE TABLE notraius.types (
    id           uuid          DEFAULT gen_random_uuid() NOT NULL,
    created_at   timestamptz   DEFAULT now() NOT NULL,
    updated_at   timestamptz   DEFAULT now() NOT NULL,
    created_by   uuid,
    entity_id    uuid          NOT NULL,
    type_id      uuid,
    code         varchar(50)   NOT NULL,
    label        varchar(100)  NOT NULL,
    description  text,
    status_id    uuid          NOT NULL,
    CONSTRAINT pk_types PRIMARY KEY (id),
    CONSTRAINT uk_types_entity_code UNIQUE (entity_id, code),
    CONSTRAINT ck_types_no_self_parent CHECK (type_id IS NULL OR type_id <> id),
    CONSTRAINT fk_types_on_entity FOREIGN KEY (entity_id) REFERENCES notraius.entities (id),
    CONSTRAINT fk_types_on_parent FOREIGN KEY (type_id) REFERENCES notraius.types (id),
    CONSTRAINT fk_types_on_status FOREIGN KEY (status_id) REFERENCES notraius.statuses (id)
);

CREATE INDEX idx_types_entity_id ON notraius.types USING btree (entity_id);
CREATE INDEX idx_types_parent_id ON notraius.types USING btree (type_id);
CREATE INDEX idx_types_status_id ON notraius.types USING btree (status_id);


-- ── 4. notraius.durations — a unit type and a value saying how many ────────
-- Identity is (type, value): HOUR + 10 created twice is ONE row (Part 2).
-- `value` stays NULLABLE exactly as DATABASE.txt 1.8 declares it: the
-- catalogue may hold drafting placeholders (HOUR + no value yet), and the
-- enforceable rule is that a duration BOUND to a pricing carries at least
-- one (service-enforced, plus the positive CHECK below for every non-null
-- value: a night of zero hours prices nothing).

CREATE TABLE notraius.durations (
    id           uuid          DEFAULT gen_random_uuid() NOT NULL,
    created_at   timestamptz   DEFAULT now() NOT NULL,
    updated_at   timestamptz   DEFAULT now() NOT NULL,
    created_by   uuid,
    type_id      uuid          NOT NULL,
    "value"      integer,
    status_id    uuid          NOT NULL,
    CONSTRAINT pk_durations PRIMARY KEY (id),
    CONSTRAINT uk_durations_type_value UNIQUE (type_id, "value"),
    CONSTRAINT ck_durations_positive_value CHECK ("value" IS NULL OR "value" > 0),
    CONSTRAINT fk_durations_on_type FOREIGN KEY (type_id) REFERENCES notraius.types (id),
    CONSTRAINT fk_durations_on_status FOREIGN KEY (status_id) REFERENCES notraius.statuses (id)
);

CREATE INDEX idx_durations_type_id ON notraius.durations USING btree (type_id);
CREATE INDEX idx_durations_status_id ON notraius.durations USING btree (status_id);


-- ── 5. notraius.amenities — the moderated catalogue ───────────────────────
-- `user_id` is the PROPOSER (null when the admin created the row directly —
-- staff birth is staff approval, so it is born ACTIVE).
-- `data_type` is immutable after birth: changing BOOLEAN to STRING would
-- reinterpret every holding's stored value.

CREATE TABLE notraius.amenities (
    id           uuid          DEFAULT gen_random_uuid() NOT NULL,
    created_at   timestamptz   DEFAULT now() NOT NULL,
    updated_at   timestamptz   DEFAULT now() NOT NULL,
    created_by   uuid,
    type_id      uuid          NOT NULL,
    data_type    varchar(12)   NOT NULL,
    code         varchar(50)   NOT NULL,
    label        varchar(100)  NOT NULL,
    description  text,
    status_id    uuid          NOT NULL,
    user_id      uuid,
    CONSTRAINT pk_amenities PRIMARY KEY (id),
    CONSTRAINT ck_amenities_data_type CHECK (data_type IN ('BOOLEAN', 'INTEGER', 'STRING')),
    CONSTRAINT fk_amenities_on_type FOREIGN KEY (type_id) REFERENCES notraius.types (id),
    CONSTRAINT fk_amenities_on_status FOREIGN KEY (status_id) REFERENCES notraius.statuses (id)
);

CREATE INDEX idx_amenities_type_id ON notraius.amenities USING btree (type_id);
CREATE INDEX idx_amenities_status_id ON notraius.amenities USING btree (status_id);
CREATE INDEX idx_amenities_code ON notraius.amenities USING btree (code);
CREATE INDEX idx_amenities_user_id ON notraius.amenities USING btree (user_id);


-- ── 6. Canonical duration units (admin-seeded vocabulary) ─────────────────
-- Part 2: "The canonical units the specification names are SECOND, MINUTE,
-- HOUR, DAY and MONTH … the admin's seeded rows." Seeding them here makes the
-- duration catalogue usable at birth; the admin still owns the alphabet and
-- may add more unit types through the types module.

INSERT INTO notraius.types (id, created_at, updated_at, entity_id, type_id, code, label, description, status_id)
SELECT gen_random_uuid(), now(), now(), e.id, NULL, v.code, v.label, v.description, s.id
FROM notraius.entities e
JOIN notraius.statuses s ON s.entity_id = e.id AND s.code = 'ACTIVE'
CROSS JOIN (VALUES
        ('SECOND', 'Second', 'Base time unit'),
        ('MINUTE', 'Minute', 'Sixty seconds'),
        ('HOUR',   'Hour',   'Sixty minutes — the unit a night is measured in'),
        ('DAY',    'Day',    'One calendar day; 23 or 25 hours across a DST edge'),
        ('MONTH',  'Month',  'One CALENDAR month — never a fixed 30 days')
     ) AS v(code, label, description)
WHERE e.code = 'DURATION_UNIT'
  AND NOT EXISTS (SELECT 1 FROM notraius.types t
                  WHERE t.entity_id = e.id AND t.code = v.code);
