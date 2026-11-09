-- ═══════════════════════════════════════════════════════════════════════════
-- V29__asset_pricings.sql — ASSET/pricing sub-module
-- ═══════════════════════════════════════════════════════════════════════════
-- PLAN.md 4.2. A pricing answers "what does one unit of this property cost, and
-- when does that answer stand": the commercial type (NIGHTLY, MONTHLY), the
-- duration that gives the type its physical body, the amount in the property's
-- currency, and the validity window the price is good for.
--
-- Schema deltas on DATABASE.txt 3.2, stated here:
--   • `checkable` joins the row (the operator's naming ruling — it replaces the
--     `requires_conditions` wording): a lease born on this pricing runs the
--     move-in/move-out condition stage of Part 6.2, or bypasses it entirely.
--   • `status_id` joins the row (PLAN.md 4.2 states the addition here, where it
--     bites): LIVE / PAUSED are persisted owner states, while EXPIRED is the
--     CLOCK's verdict and is therefore derived at read time from the window —
--     never stamped as a second truth a nightly job and a request could
--     disagree about.
--   • `uk_pricings_property_type` makes the operator's one-pricing law a
--     database law: (property, type) is a lifetime identity, so a second
--     nightly row for the same property is unrepresentable. A price change is
--     a new WINDOW and a new VERSION of that one row, never a sibling.
--   • `ck_pricings_amount_positive` makes the zero-amount ruling absolute at
--     the storage layer too: there is no free promotional month and no "price
--     it later" draft.
-- ═══════════════════════════════════════════════════════════════════════════


-- ── 1. Entity + status vocabulary ──────────────────────────────────────────
-- The PRICINGS entity is both the pricing row's status namespace and the
-- namespace of the commercial-type catalogue (MONTHLY, NIGHTLY, WEEKLY, …):
-- a pricing's type is a type row under PRICINGS, exactly as a duration's unit
-- is a type row under DURATION_UNIT (Part 1's entity rule).
--
-- The same vocabulary types the VALIDITY rows the windows live in: an open
-- window is LIVE, a paused pricing's window is PAUSED. EXPIRED is deliberately
-- absent from the table — it is what the window says, not what a row says.

INSERT INTO notraius.entities (id, created_at, updated_at, code)
SELECT gen_random_uuid(), now(), now(), 'PRICINGS'
WHERE NOT EXISTS (SELECT 1 FROM notraius.entities e WHERE e.code = 'PRICINGS');

INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
SELECT gen_random_uuid(), now(), now(), v.code, v.description, e.id
FROM notraius.entities e
JOIN (VALUES
        ('LIVE',   'Price answers while its window contains the present instant'),
        ('PAUSED', 'Deliberately stopped by the owner; the clock did not do it')
     ) AS v(code, description) ON TRUE
WHERE e.code = 'PRICINGS'
  AND NOT EXISTS (SELECT 1 FROM notraius.statuses s
                  WHERE s.entity_id = e.id AND s.code = v.code);


-- ── 2. property.pricings ──────────────────────────────────────────────────
-- The amount is in the PROPERTY's currency (never a column here — money's unit
-- is inherited, so a pricing can never disagree with its property about it).
-- The duration is the physics (ten-hour nights), the type is the market's word
-- for it, and the validity is the answer's window.

CREATE TABLE property.pricings (
    id           uuid          DEFAULT gen_random_uuid() NOT NULL,
    created_at   timestamptz   DEFAULT now() NOT NULL,
    updated_at   timestamptz   DEFAULT now() NOT NULL,
    created_by   uuid,
    property_id  uuid          NOT NULL,
    type_id      uuid          NOT NULL,
    duration_id  uuid          NOT NULL,
    amount       numeric(15, 2) NOT NULL,
    checkable    boolean       DEFAULT false NOT NULL,
    version      integer       DEFAULT 1 NOT NULL,
    validity_id  uuid          NOT NULL,
    status_id    uuid          NOT NULL,
    CONSTRAINT pk_pricings PRIMARY KEY (id),
    -- One pricing per property per type — ever (the operator's one-pricing law).
    CONSTRAINT uk_pricings_property_type UNIQUE (property_id, type_id),
    CONSTRAINT ck_pricings_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_pricings_version_positive CHECK (version > 0),
    CONSTRAINT fk_pricings_on_property FOREIGN KEY (property_id)
        REFERENCES property.properties (id),
    CONSTRAINT fk_pricings_on_type FOREIGN KEY (type_id) REFERENCES notraius.types (id),
    CONSTRAINT fk_pricings_on_duration FOREIGN KEY (duration_id) REFERENCES notraius.durations (id),
    CONSTRAINT fk_pricings_on_validity FOREIGN KEY (validity_id) REFERENCES notraius.validity (id),
    CONSTRAINT fk_pricings_on_status FOREIGN KEY (status_id) REFERENCES notraius.statuses (id)
);

CREATE INDEX idx_pricings_property_id ON property.pricings USING btree (property_id);
CREATE INDEX idx_pricings_type_id ON property.pricings USING btree (type_id);
CREATE INDEX idx_pricings_duration_id ON property.pricings USING btree (duration_id);
CREATE INDEX idx_pricings_validity_id ON property.pricings USING btree (validity_id);
CREATE INDEX idx_pricings_status_id ON property.pricings USING btree (status_id);
