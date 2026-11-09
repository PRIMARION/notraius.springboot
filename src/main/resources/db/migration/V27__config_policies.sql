-- ═══════════════════════════════════════════════════════════════════════════
-- V27__config_policies.sql — CONFIG/policies sub-module
-- ═══════════════════════════════════════════════════════════════════════════
-- PLAN.md 3.2. A policy is a STANDARD: "for this kind of thing, here is the
-- allowed range and whether you must comply at all". Admin-managed end to end;
-- the whole user-facing surface is reading it.
--
-- Schema deltas on DATABASE.txt 1.5, stated here:
--   • `unit` joins the row (PLAN.md 3.2, "one more column joins in this
--     upgrade"): UNITS means the value counts units of the bound pricing's
--     duration, AMOUNT means a raw amount of money in the property's currency.
--     The lease/deposit machinery reads this column, so it is NOT NULL.
--   • `version` joins the row: policy attachments are versioned with the
--     policy ("what did the standard say, and what document backed it, at
--     version four"), so every edit is a new truth exactly as a rule's is.
--   • `min` / `max` keep the schema's names; they are quoted identifiers
--     (globally_quoted_identifiers) and the entity maps them as minimum and
--     maximum.
--   • `state_id` stays a bare UUID: states live in the shared lookup database,
--     so there is no local foreign key to hang it on.
--   • the code's identity is (entity, code, STATE), not (entity, code): 4.3's
--     whole point is that Benin's ADVANCE and Nigeria's ADVANCE are two rows
--     with the same code, each governing its own world. It is spelled as two
--     PARTIAL unique indexes because a plain UNIQUE(…, state_id) treats every
--     NULL as distinct in PostgreSQL, which would let two stateless ADVANCE
--     rows coexist — the opposite defect.
-- ═══════════════════════════════════════════════════════════════════════════


-- ── 1. Entity + status vocabulary ──────────────────────────────────────────
-- ENABLED / DISABLED only: disable is the admin's pause button and re-enabling
-- restores enforcement, so the status is a two-position switch. There is no
-- draft state (a policy is created enabled or not at all) and no delete state
-- (values already set against a policy are a property's history).

INSERT INTO notraius.entities (id, created_at, updated_at, code)
SELECT gen_random_uuid(), now(), now(), 'POLICIES'
WHERE NOT EXISTS (SELECT 1 FROM notraius.entities e WHERE e.code = 'POLICIES');

INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
SELECT gen_random_uuid(), now(), now(), v.code, v.description, e.id
FROM notraius.entities e
JOIN (VALUES
        ('ENABLED',  'Standard in force: offered for value-setting and enforced'),
        ('DISABLED', 'Standard paused: not offered, not enforced for new configurations')
     ) AS v(code, description) ON TRUE
WHERE e.code = 'POLICIES'
  AND NOT EXISTS (SELECT 1 FROM notraius.statuses s
                  WHERE s.entity_id = e.id AND s.code = v.code);


-- ── 2. notraius.policies ──────────────────────────────────────────────────
-- `entity_id` + `type_id` say WHAT the standard governs (ADVANCE is a policy
-- of type INSTALLMENT), `state_id` scopes it geographically (a Benin standard
-- may differ from a Nigerian one; null is the stateless fallback of Part 4.3),
-- and `unit` says how the value is READ.

CREATE TABLE notraius.policies (
    id           uuid          DEFAULT gen_random_uuid() NOT NULL,
    created_at   timestamptz   DEFAULT now() NOT NULL,
    updated_at   timestamptz   DEFAULT now() NOT NULL,
    created_by   uuid,
    entity_id    uuid          NOT NULL,
    type_id      uuid          NOT NULL,
    title        varchar(100)  NOT NULL,
    code         varchar(50)   NOT NULL,
    description  text,
    min          numeric(15, 2),
    max          numeric(15, 2),
    mandatory    boolean       DEFAULT false NOT NULL,
    unit         varchar(12)   NOT NULL,
    version      integer       DEFAULT 1 NOT NULL,
    state_id     uuid,
    status_id    uuid          NOT NULL,
    CONSTRAINT pk_policies PRIMARY KEY (id),
    CONSTRAINT ck_policies_bounds CHECK (min IS NULL OR max IS NULL OR min <= max),
    CONSTRAINT ck_policies_unit CHECK (unit IN ('UNITS', 'AMOUNT')),
    CONSTRAINT ck_policies_version_positive CHECK (version > 0),
    CONSTRAINT fk_policies_on_entity FOREIGN KEY (entity_id) REFERENCES notraius.entities (id),
    CONSTRAINT fk_policies_on_type FOREIGN KEY (type_id) REFERENCES notraius.types (id),
    CONSTRAINT fk_policies_on_status FOREIGN KEY (status_id) REFERENCES notraius.statuses (id)
);

-- The code identity, state included: one stateless standard per code, and one
-- standard per (code, state) — never two answers for the same world.
CREATE UNIQUE INDEX uk_policies_entity_code_stateless
    ON notraius.policies USING btree (entity_id, code) WHERE state_id IS NULL;
CREATE UNIQUE INDEX uk_policies_entity_code_state
    ON notraius.policies USING btree (entity_id, code, state_id) WHERE state_id IS NOT NULL;

CREATE INDEX idx_policies_entity_id ON notraius.policies USING btree (entity_id);
CREATE INDEX idx_policies_type_id ON notraius.policies USING btree (type_id);
CREATE INDEX idx_policies_status_id ON notraius.policies USING btree (status_id);
CREATE INDEX idx_policies_state_id ON notraius.policies USING btree (state_id);
CREATE INDEX idx_policies_code ON notraius.policies USING btree (code);
