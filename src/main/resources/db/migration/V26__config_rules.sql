-- ═══════════════════════════════════════════════════════════════════════════
-- V26__config_rules.sql — CONFIG/rules sub-module
-- ═══════════════════════════════════════════════════════════════════════════
-- PLAN.md 3.1. Rules describe what living in a place may be like. They are
-- USER-OWNED with a visibility the creator picks at birth, and they version:
-- every edit is a new truth whose history is reconstituted from the log chain.
--
-- Schema deltas on DATABASE.txt 1.6, stated here:
--   • `version` is NOT NULL DEFAULT 1 — birth IS version one, so the log
--     replay always has a base (PLAN.md 3.1, "creation is itself the first
--     entry").
--   • `user_id` is renamed `creator_id` in the entity mapping only; the column
--     keeps the schema's name (it is the creator, never a generic user).
--   • `created_by`/`created_at`/`updated_at` join every row per the general
--     rules.
-- ═══════════════════════════════════════════════════════════════════════════


-- ── 1. Entity + status vocabulary ──────────────────────────────────────────
-- ACTIVE / DISABLED / RETIRED: disable is the reversible brake the in-use lock
-- guards, and retire is the exit taken once nothing uses the rule any more.
-- There is deliberately no DELETE state and no delete endpoint — PLAN.md 3.1:
-- "hard deletion of rules is not part of this plan at all", because history
-- must stay replayable.

INSERT INTO notraius.entities (id, created_at, updated_at, code)
SELECT gen_random_uuid(), now(), now(), 'RULES'
WHERE NOT EXISTS (SELECT 1 FROM notraius.entities e WHERE e.code = 'RULES');

INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
SELECT gen_random_uuid(), now(), now(), v.code, v.description, e.id
FROM notraius.entities e
JOIN (VALUES
        ('ACTIVE',   'Rule in force; its properties read the current version'),
        ('DISABLED', 'Rule switched off by its creator; reversible'),
        ('RETIRED',  'Rule out of every catalogue for good; history stays replayable')
     ) AS v(code, description) ON TRUE
WHERE e.code = 'RULES'
  AND NOT EXISTS (SELECT 1 FROM notraius.statuses s
                  WHERE s.entity_id = e.id AND s.code = v.code);


-- ── 2. notraius.rules ──────────────────────────────────────────────────────
-- Visibility is exactly two lights: PUBLIC (every authenticated user may read
-- and select it) and PRIVATE (its creator alone — plus the property that has
-- already selected it, which is the operator's chicken-and-egg exception, and
-- staff moderation, which is read authority and never adoption).

CREATE TABLE notraius.rules (
    id           uuid          DEFAULT gen_random_uuid() NOT NULL,
    created_at   timestamptz   DEFAULT now() NOT NULL,
    updated_at   timestamptz   DEFAULT now() NOT NULL,
    created_by   uuid,
    user_id      uuid          NOT NULL,
    title        varchar(100)  NOT NULL,
    description  text          NOT NULL,
    visibility   varchar(12)   NOT NULL,
    version      integer       DEFAULT 1 NOT NULL,
    status_id    uuid          NOT NULL,
    CONSTRAINT pk_rules PRIMARY KEY (id),
    CONSTRAINT ck_rules_visibility CHECK (visibility IN ('PUBLIC', 'PRIVATE')),
    CONSTRAINT ck_rules_version_positive CHECK (version > 0),
    CONSTRAINT fk_rules_on_status FOREIGN KEY (status_id) REFERENCES notraius.statuses (id)
);

CREATE INDEX idx_rules_user_id ON notraius.rules USING btree (user_id);
CREATE INDEX idx_rules_status_id ON notraius.rules USING btree (status_id);
CREATE INDEX idx_rules_visibility ON notraius.rules USING btree (visibility);
