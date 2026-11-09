-- ═══════════════════════════════════════════════════════════════════════════
-- V28__asset_properties.sql — ASSET/property sub-module
-- ═══════════════════════════════════════════════════════════════════════════
-- PLAN.md 4.1. A property is the asset every later money flow hangs from: the
-- visit prices itself against it, the lease is born on one of its pricings, and
-- the deposit standards are valued on those pricings. It is therefore the row
-- whose two anchors — location and the currency that location implies — are born
-- immutable, because everything beneath it inherits them.
--
-- Schema deltas on DATABASE.txt 3.1, stated here:
--   • the `property` schema itself is created by this migration: DATABASE.txt
--     describes it but no earlier migration owns it (this is the module that
--     brings the schema into existence).
--   • `state_id` joins the row. The chain is location → state → currency and
--     the policy resolution of 4.3 matches a property against state-scoped
--     standards through this column. It is resolved once, at the moment the
--     location is set, and never re-derived (the location never changes).
--   • `version` joins the row: every accepted edit is a new truth whose
--     before/after lives in the log chain (the rules/policies discipline).
--   • `user_id` stays the schema's name and IS the owner — the single truth of
--     ownership the visit module's owner-settlement rule reads.
--
-- The anchor invariant is enforced by the database, not by convention:
-- a property either has NO anchors (a draft that has not been given an address
-- yet) or ALL THREE (location, state, currency). A half-anchor cannot exist, so
-- the stateless middle ground 4.3 forbids is unrepresentable.
-- ═══════════════════════════════════════════════════════════════════════════

CREATE SCHEMA IF NOT EXISTS property;


-- ── 1. Entity + status vocabulary ──────────────────────────────────────────
-- DRAFT is the save-first state; PUBLISHED is the market; PAUSED is the owner's
-- own unpublish; SUSPENDED is staff moderation (the owner cannot lift it alone);
-- DELETED is the soft removal that detaches media instead of orphaning it.
-- The PROPERTY entity also namespaces the property KIND catalogue's types
-- (Part 1/4.1: "the property's type is bound to the PROPERTY entity").

INSERT INTO notraius.entities (id, created_at, updated_at, code)
SELECT gen_random_uuid(), now(), now(), 'PROPERTY'
WHERE NOT EXISTS (SELECT 1 FROM notraius.entities e WHERE e.code = 'PROPERTY');

INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
SELECT gen_random_uuid(), now(), now(), v.code, v.description, e.id
FROM notraius.entities e
JOIN (VALUES
        ('DRAFT',     'Saved but incomplete: invisible to the market, editable at leisure'),
        ('PUBLISHED', 'Live: discoverable, viewable and bookable'),
        ('PAUSED',    'The owner''s own unpublish: everything persists, nothing is offered'),
        ('SUSPENDED', 'Held by staff moderation; only staff can lift it'),
        ('DELETED',   'Soft removal; media detached and references retired, never broken')
     ) AS v(code, description) ON TRUE
WHERE e.code = 'PROPERTY'
  AND NOT EXISTS (SELECT 1 FROM notraius.statuses s
                  WHERE s.entity_id = e.id AND s.code = v.code);


-- ── 2. property.properties ────────────────────────────────────────────────
-- `type_id` is nullable on purpose: a draft may be saved with a title alone,
-- and the type is part of the PUBLICATION completeness gate, not of saving.
-- `currency_id` points into pay's currency catalogue (money lives in pay, the
-- same way states live in the shared lookup); it is never chosen by a user —
-- it is derived from the resolved state at the backend.

CREATE TABLE property.properties (
    id           uuid          DEFAULT gen_random_uuid() NOT NULL,
    created_at   timestamptz   DEFAULT now() NOT NULL,
    updated_at   timestamptz   DEFAULT now() NOT NULL,
    created_by   uuid,
    title        varchar(255)  NOT NULL,
    user_id      uuid          NOT NULL,
    status_id    uuid          NOT NULL,
    type_id      uuid,
    currency_id  uuid,
    description  text,
    location_id  uuid,
    state_id     uuid,
    version      integer       DEFAULT 1 NOT NULL,
    CONSTRAINT pk_properties PRIMARY KEY (id),
    CONSTRAINT ck_properties_version_positive CHECK (version > 0),
    -- The anchors travel together: no anchors at all, or the full chain.
    CONSTRAINT ck_properties_anchors_together CHECK (
        (location_id IS NULL AND state_id IS NULL AND currency_id IS NULL)
        OR (location_id IS NOT NULL AND state_id IS NOT NULL AND currency_id IS NOT NULL)),
    CONSTRAINT fk_properties_on_status FOREIGN KEY (status_id) REFERENCES notraius.statuses (id),
    CONSTRAINT fk_properties_on_type FOREIGN KEY (type_id) REFERENCES notraius.types (id)
);

CREATE INDEX idx_properties_user_id ON property.properties USING btree (user_id);
CREATE INDEX idx_properties_status_id ON property.properties USING btree (status_id);
CREATE INDEX idx_properties_type_id ON property.properties USING btree (type_id);
CREATE INDEX idx_properties_state_id ON property.properties USING btree (state_id);
CREATE INDEX idx_properties_location_id ON property.properties USING btree (location_id);
CREATE INDEX idx_properties_currency_id ON property.properties USING btree (currency_id);
CREATE INDEX idx_properties_title ON property.properties USING btree (lower(title));


-- ── 3. property.amenities ─────────────────────────────────────────────────
-- The owner's holdings: "this property has a pool, value true". The holding is
-- a row of its own with its own status, exactly as DATABASE.txt 3.6 says, and
-- the status carries the one distinction the cascade law needs: DISABLED is the
-- owner's own choice, SUPPRESSED is the catalogue switching the amenity off
-- underneath (Part 3.3). Keeping them apart is what lets re-enabling the global
-- amenity restore only what the catalogue took, never what the owner refused.
--
-- No validity window: a holding is present or not; its lifecycle is its status.

INSERT INTO notraius.entities (id, created_at, updated_at, code)
SELECT gen_random_uuid(), now(), now(), 'PROPERTY_AMENITIES'
WHERE NOT EXISTS (SELECT 1 FROM notraius.entities e WHERE e.code = 'PROPERTY_AMENITIES');

INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
SELECT gen_random_uuid(), now(), now(), v.code, v.description, e.id
FROM notraius.entities e
JOIN (VALUES
        ('ACTIVE',     'Held and offered by the property'),
        ('DISABLED',   'Switched off by the owner; their own choice, never the catalogue''s'),
        ('SUPPRESSED', 'Switched off by the catalogue disabling the amenity; reversible only by the catalogue')
     ) AS v(code, description) ON TRUE
WHERE e.code = 'PROPERTY_AMENITIES'
  AND NOT EXISTS (SELECT 1 FROM notraius.statuses s
                  WHERE s.entity_id = e.id AND s.code = v.code);

CREATE TABLE property.amenities (
    id           uuid          DEFAULT gen_random_uuid() NOT NULL,
    created_at   timestamptz   DEFAULT now() NOT NULL,
    updated_at   timestamptz   DEFAULT now() NOT NULL,
    created_by   uuid,
    property_id  uuid          NOT NULL,
    amenity_id   uuid          NOT NULL,
    value        varchar(255),
    status_id    uuid          NOT NULL,
    CONSTRAINT pk_property_amenities PRIMARY KEY (id),
    CONSTRAINT uk_property_amenities_pair UNIQUE (property_id, amenity_id),
    CONSTRAINT fk_property_amenities_on_property FOREIGN KEY (property_id)
        REFERENCES property.properties (id),
    CONSTRAINT fk_property_amenities_on_amenity FOREIGN KEY (amenity_id)
        REFERENCES notraius.amenities (id),
    CONSTRAINT fk_property_amenities_on_status FOREIGN KEY (status_id)
        REFERENCES notraius.statuses (id)
);

CREATE INDEX idx_property_amenities_property_id ON property.amenities USING btree (property_id);
CREATE INDEX idx_property_amenities_amenity_id ON property.amenities USING btree (amenity_id);
CREATE INDEX idx_property_amenities_status_id ON property.amenities USING btree (status_id);
