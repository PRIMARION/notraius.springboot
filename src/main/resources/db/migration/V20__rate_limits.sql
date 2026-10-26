-- =====================================================================
-- V20 — rate.limits / rate.ledger: the fully database-driven rate
-- limiter (UPGRADE.txt rate-limit item).
--
-- One rule row = one (activity, operation, scope) triple = one counter
-- window. Rules are admin-managed: presence of a row ENABLES limiting,
-- an activity/operation with no row is unlimited, and an admin
-- disables a limit by deleting its row. Counters live in rate.ledger
-- as (rule, subject) rows written through one window-aware atomic
-- upsert; the shared RateLimitCleanupJob sweeps expired rows.
--
-- ledger.limit_id is deliberately a PLAIN uuid with no foreign key:
-- an admin must be able to delete a rule even while its counters are
-- still on the clock.
--
-- The retired security.rate_limit table (flat user/activity counters
-- fed by env/YAML configuration) is dropped with every leftover purge
-- job that referenced it.
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS rate;

CREATE TABLE rate.limits (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    activity_id uuid NOT NULL,
    operation character varying(50) NOT NULL,
    scope character varying(16) NOT NULL,
    timeframe integer NOT NULL,
    max_count integer NOT NULL
);

ALTER TABLE ONLY rate.limits
    ADD CONSTRAINT pk_rate_limits PRIMARY KEY (id);

ALTER TABLE ONLY rate.limits
    ADD CONSTRAINT uc_rate_limits_activity_op_scope UNIQUE (activity_id, operation, scope);

ALTER TABLE ONLY rate.limits
    ADD CONSTRAINT fk_rate_limits_activity FOREIGN KEY (activity_id) REFERENCES access.activities(id);

CREATE TABLE rate.ledger (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    limit_id uuid NOT NULL,
    subject character varying(255) NOT NULL,
    count integer NOT NULL,
    refresh_at timestamp with time zone NOT NULL
);

ALTER TABLE ONLY rate.ledger
    ADD CONSTRAINT pk_rate_ledger PRIMARY KEY (id);

ALTER TABLE ONLY rate.ledger
    ADD CONSTRAINT uc_rate_ledger_limit_subject UNIQUE (limit_id, subject);

CREATE INDEX idx_rate_ledger_refresh_at ON rate.ledger USING btree (refresh_at);

DROP TABLE IF EXISTS security.rate_limit;
