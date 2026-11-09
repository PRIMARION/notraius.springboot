-- ═══════════════════════════════════════════════════════════════════════════
-- V30__asset_terms.sql — ASSET/terms sub-module
-- ═══════════════════════════════════════════════════════════════════════════
-- PLAN.md 4.3 and 4.1's selection paragraphs. "Terms" is this module's name
-- because that is what these rows are: the property's own law — the house rules
-- it holds (property.rules, DATABASE.txt 3.5) and the admin standards valued
-- against its pricings (property.policies, DATABASE.txt 3.3). Amenities are not
-- terms; they are the property's facilities and live in the property sub-module.
--
-- Schema deltas on DATABASE.txt, stated here:
--   • `uk_property_policies_pricing_policy` makes "the same policy valued twice
--     on one pricing is one row, not two" (4.3) the storage law, so a
--     concurrent double-set converges on one identity instead of two truths.
--   • `rule_id` becomes a real foreign key to notraius.rules. DATABASE.txt calls
--     it a bare reference ("lien vers référentiel"), but the referential exists
--     in this very database — a dangling rule id is a corrupt selection, and the
--     database is the last place to allow one.
--   • no unique constraint on (property_id, rule_id): 4.1 is explicit that one
--     property may hold the same rule for DIFFERENT windows, so the pair is not
--     the identity of a selection — the row is.
-- ═══════════════════════════════════════════════════════════════════════════


-- ── 1. Entity + status vocabularies ────────────────────────────────────────
-- PROPERTY_POLICIES: ACTIVE is a compliant value in force; PAUSED is a value
-- whose standard the admin disabled (values persist, cannot be edited while
-- paused, resume under re-enabling); FLAGGED is a value the admin's tightened
-- bounds have since excluded — visible for review, never silently rewritten.
--
-- PROPERTY_RULES: the selection's lifecycle lives in its VALIDITY row (the
-- estate's never-null validity model, #EA-08), so this entity namespaces the
-- statuses that row can carry. There is no owner pause: a window says when the
-- rule applies, and removing the selection is the owner's exit.

INSERT INTO notraius.entities (id, created_at, updated_at, code)
SELECT gen_random_uuid(), now(), now(), v.code
FROM (VALUES ('PROPERTY_POLICIES'), ('PROPERTY_RULES')) AS v(code)
WHERE NOT EXISTS (SELECT 1 FROM notraius.entities e WHERE e.code = v.code);

INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
SELECT gen_random_uuid(), now(), now(), v.code, v.description, e.id
FROM notraius.entities e
JOIN (VALUES
        ('ACTIVE',  'Value in force and inside its standard''s bounds'),
        ('PAUSED',  'The standard behind this value is paused; the value is kept, not erased'),
        ('FLAGGED', 'The standard''s bounds have moved; the value needs review, never clamped')
     ) AS v(code, description) ON TRUE
WHERE e.code = 'PROPERTY_POLICIES'
  AND NOT EXISTS (SELECT 1 FROM notraius.statuses s
                  WHERE s.entity_id = e.id AND s.code = v.code);

INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
SELECT gen_random_uuid(), now(), now(), v.code, v.description, e.id
FROM notraius.entities e
JOIN (VALUES
        ('ACTIVE', 'Selection in force while its window contains the present instant')
     ) AS v(code, description) ON TRUE
WHERE e.code = 'PROPERTY_RULES'
  AND NOT EXISTS (SELECT 1 FROM notraius.statuses s
                  WHERE s.entity_id = e.id AND s.code = v.code);


-- ── 2. property.policies — the owner's values against admin standards ─────
-- The number is the OWNER's; the bounds are the ADMIN's; the pricing is the
-- context that gives the number its unit (four MONTHS because the pricing is
-- monthly). The currency is the property's, inherited through the pricing.

CREATE TABLE property.policies (
    id           uuid          DEFAULT gen_random_uuid() NOT NULL,
    created_at   timestamptz   DEFAULT now() NOT NULL,
    updated_at   timestamptz   DEFAULT now() NOT NULL,
    created_by   uuid,
    pricing_id   uuid          NOT NULL,
    policy_id    uuid          NOT NULL,
    value        numeric(15, 2),
    status_id    uuid          NOT NULL,
    version      integer       DEFAULT 1 NOT NULL,
    CONSTRAINT pk_property_policies PRIMARY KEY (id),
    CONSTRAINT uk_property_policies_pricing_policy UNIQUE (pricing_id, policy_id),
    CONSTRAINT ck_property_policies_value_non_negative CHECK (value IS NULL OR value >= 0),
    CONSTRAINT ck_property_policies_version_positive CHECK (version > 0),
    CONSTRAINT fk_property_policies_on_pricing FOREIGN KEY (pricing_id)
        REFERENCES property.pricings (id),
    CONSTRAINT fk_property_policies_on_policy FOREIGN KEY (policy_id)
        REFERENCES notraius.policies (id),
    CONSTRAINT fk_property_policies_on_status FOREIGN KEY (status_id)
        REFERENCES notraius.statuses (id)
);

CREATE INDEX idx_property_policies_pricing_id ON property.policies USING btree (pricing_id);
CREATE INDEX idx_property_policies_policy_id ON property.policies USING btree (policy_id);
CREATE INDEX idx_property_policies_status_id ON property.policies USING btree (status_id);


-- ── 3. property.rules — the selections the property holds ──────────────────
-- Each selection is a row of its own with its own window and its own version,
-- so two properties holding the same rule hold two independent selections and
-- one property may hold the same rule for two different windows. Once a rule IS
-- selected, the property's read path shows it to every viewer regardless of the
-- rule's visibility (3.1's chicken-and-egg ruling).

CREATE TABLE property.rules (
    id           uuid          DEFAULT gen_random_uuid() NOT NULL,
    created_at   timestamptz   DEFAULT now() NOT NULL,
    updated_at   timestamptz   DEFAULT now() NOT NULL,
    created_by   uuid,
    property_id  uuid          NOT NULL,
    rule_id      uuid          NOT NULL,
    validity_id  uuid          NOT NULL,
    version      integer       DEFAULT 1 NOT NULL,
    CONSTRAINT pk_property_rules PRIMARY KEY (id),
    CONSTRAINT ck_property_rules_version_positive CHECK (version > 0),
    CONSTRAINT fk_property_rules_on_property FOREIGN KEY (property_id)
        REFERENCES property.properties (id),
    CONSTRAINT fk_property_rules_on_rule FOREIGN KEY (rule_id) REFERENCES notraius.rules (id),
    CONSTRAINT fk_property_rules_on_validity FOREIGN KEY (validity_id)
        REFERENCES notraius.validity (id)
);

CREATE INDEX idx_property_rules_property_id ON property.rules USING btree (property_id);
CREATE INDEX idx_property_rules_rule_id ON property.rules USING btree (rule_id);
CREATE INDEX idx_property_rules_validity_id ON property.rules USING btree (validity_id);
