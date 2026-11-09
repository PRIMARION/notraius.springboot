-- V31__visit_request.sql — Part 5.1: the visit pricing catalogue and the visit
-- row that a paid request creates.
--
-- The two tables are the DATABASE.txt 4.1/4.2 shapes, plus the stated deltas the
-- plan's money paragraph forces:
--
--   4.2 `visit.visits` gains `owner_id`, `amount`, `currency_id`, `payment_id`
--       and `payment_split_id`:
--         • `owner_id` is the BENEFICIARY SNAPSHOT — the user id sitting on the
--           property row at request time. PLAN 5.0 is adamant that the visit's
--           own `user_id` is the operational counterpart, never the payee, and
--           settlement must not have to guess which row was true when the money
--           was taken.
--         • `amount` + `currency_id` are the CHARGE SNAPSHOT. The pricing may be
--           re-priced tomorrow; a paid visit must keep answering with the number
--           and the money it was sold in.
--         • `payment_id` + `payment_split_id` are the two custody addresses pay
--           returns at birth. The reference dedupe key is (visit id, currency),
--           so the payments of one visitor's two visits can never converge; the
--           address columns are what later stages release or refund.
--
-- The currency is UUID, exactly like property.properties.currency_id: money is
-- pay's to name, and notraius only carries the reference.
--
-- Statuses: the visit's flow is REQUESTED → ASSIGNED → PLANNING → CONFIRMED →
-- STARTED → COMPLETED, with EXPIRED and CANCELLED as the terminal exits, plus
-- UNPAID — the row a funding call left without an answer. UNPAID is a state,
-- not an error: PLAN 5.1 says an unknown must not roll the row back, because
-- pay may have taken the money, so the visit stands, clearly unfunded-looking,
-- until the sweep re-reads pay's truth.

CREATE SCHEMA IF NOT EXISTS visit;

-- ── 1. Entities: the visit pricing catalogue and the visit itself ───────────
INSERT INTO notraius.entities (id, created_at, updated_at, code)
SELECT gen_random_uuid(), now(), now(), v.code
FROM (VALUES
        ('VISIT_PRICINGS'),
        ('VISITS')
     ) AS v(code)
WHERE NOT EXISTS (SELECT 1 FROM notraius.entities e WHERE e.code = v.code);

-- ── 2. Statuses ────────────────────────────────────────────────────────────
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
SELECT gen_random_uuid(), now(), now(), v.code, v.description, e.id
FROM notraius.entities e
JOIN (VALUES
        ('ACTIVE', 'The visit pricing is offered on the property'),
        ('PAUSED', 'The owner stopped offering this visit kind; the window still stands')
     ) AS v(code, description) ON TRUE
WHERE e.code = 'VISIT_PRICINGS'
  AND NOT EXISTS (SELECT 1 FROM notraius.statuses s WHERE s.entity_id = e.id AND s.code = v.code);

INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
SELECT gen_random_uuid(), now(), now(), v.code, v.description, e.id
FROM notraius.entities e
JOIN (VALUES
        ('UNPAID',    'The funding call left without a definitive answer; the sweep re-reads pay before deciding'),
        ('REQUESTED', 'Paid and waiting for the owner to assign a counterpart'),
        ('ASSIGNED',  'A counterpart was named and has not accepted yet'),
        ('PLANNING',  'Both parties are agreeing on the time'),
        ('CONFIRMED', 'The time is agreed and the visit will happen'),
        ('STARTED',   'One party claims the visit is happening'),
        ('COMPLETED', 'Both parties completed their assessment and the custody was conveyed'),
        ('EXPIRED',   'A clock ran out; the visitor was refunded in full'),
        ('CANCELLED', 'A party ended the visit; the cancellation rule decided the split')
     ) AS v(code, description) ON TRUE
WHERE e.code = 'VISITS'
  AND NOT EXISTS (SELECT 1 FROM notraius.statuses s WHERE s.entity_id = e.id AND s.code = v.code);

-- ── 3. Visit kinds: physical or virtual ────────────────────────────────────
INSERT INTO notraius.types (id, created_at, updated_at, entity_id, type_id, code, label, description, status_id)
SELECT gen_random_uuid(), now(), now(), e.id, NULL, v.code, v.label, v.description, s.id
FROM notraius.entities e
JOIN notraius.statuses s ON s.entity_id = e.id AND s.code = 'ACTIVE'
CROSS JOIN (VALUES
        ('PHYSICAL', 'Physical', 'The visitor comes to the property'),
        ('VIRTUAL',  'Virtual',  'The visit happens over a call')
     ) AS v(code, label, description)
WHERE e.code = 'VISIT_PRICINGS'
  AND NOT EXISTS (SELECT 1 FROM notraius.types t WHERE t.entity_id = e.id AND t.code = v.code);

-- ── 4. visit.pricings ──────────────────────────────────────────────────────
-- One row per (property, kind): the owner offers ONE physical and ONE virtual
-- visit price, exactly as a property has one monthly and one nightly price.
-- `duration_minutes` is a plain integer: a visit is minutes of somebody's time,
-- not a rentable unit, so it does not ride the duration catalogue.
CREATE TABLE visit.pricings (
    id               uuid          DEFAULT gen_random_uuid() NOT NULL,
    created_at       timestamptz   DEFAULT now() NOT NULL,
    updated_at       timestamptz   DEFAULT now() NOT NULL,
    created_by       uuid,
    property_id      uuid          NOT NULL,
    type_id          uuid          NOT NULL,
    amount           numeric(15, 2) NOT NULL,
    duration_minutes integer       NOT NULL,
    version          integer       DEFAULT 1 NOT NULL,
    status_id        uuid          NOT NULL,
    CONSTRAINT pk_visit_pricings PRIMARY KEY (id),
    CONSTRAINT uk_visit_pricings_property_type UNIQUE (property_id, type_id),
    CONSTRAINT ck_visit_pricings_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_visit_pricings_duration_positive CHECK (duration_minutes > 0),
    CONSTRAINT ck_visit_pricings_version_positive CHECK (version > 0),
    CONSTRAINT fk_visit_pricings_on_property FOREIGN KEY (property_id)
        REFERENCES property.properties (id),
    CONSTRAINT fk_visit_pricings_on_type FOREIGN KEY (type_id) REFERENCES notraius.types (id),
    CONSTRAINT fk_visit_pricings_on_status FOREIGN KEY (status_id)
        REFERENCES notraius.statuses (id)
);

CREATE INDEX idx_visit_pricings_property_id ON visit.pricings USING btree (property_id);
CREATE INDEX idx_visit_pricings_type_id ON visit.pricings USING btree (type_id);
CREATE INDEX idx_visit_pricings_status_id ON visit.pricings USING btree (status_id);

-- ── 5. visit.visits ────────────────────────────────────────────────────────
-- `user_id` stays the DATABASE.txt column and keeps its exact meaning: the
-- operational counterpart attached to the visit (the assignee once 5.2 stamps
-- one). It is NOT the beneficiary — `owner_id` is, and that distinction is the
-- module's first data rule (PLAN 5.0).
CREATE TABLE visit.visits (
    id               uuid          DEFAULT gen_random_uuid() NOT NULL,
    created_at       timestamptz   DEFAULT now() NOT NULL,
    updated_at       timestamptz   DEFAULT now() NOT NULL,
    created_by       uuid,
    visitor_id       uuid          NOT NULL,
    user_id          uuid,
    owner_id         uuid          NOT NULL,
    pricing_id       uuid          NOT NULL,
    validity_id      uuid          NOT NULL,
    amount           numeric(15, 2) NOT NULL,
    currency_id      uuid          NOT NULL,
    payment_id       uuid,
    payment_split_id uuid,
    version          integer       DEFAULT 1 NOT NULL,
    status_id        uuid          NOT NULL,
    CONSTRAINT pk_visits PRIMARY KEY (id),
    CONSTRAINT ck_visits_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_visits_version_positive CHECK (version > 0),
    CONSTRAINT fk_visits_on_pricing FOREIGN KEY (pricing_id) REFERENCES visit.pricings (id),
    CONSTRAINT fk_visits_on_validity FOREIGN KEY (validity_id)
        REFERENCES notraius.validity (id),
    CONSTRAINT fk_visits_on_status FOREIGN KEY (status_id) REFERENCES notraius.statuses (id)
);

-- The payment reference is the visit id, so one payment may never answer to two
-- visits: the unique index is the dedupe key's local anchor.
CREATE UNIQUE INDEX uk_visits_payment_id ON visit.visits USING btree (payment_id)
    WHERE payment_id IS NOT NULL;
CREATE INDEX idx_visits_visitor_id ON visit.visits USING btree (visitor_id);
CREATE INDEX idx_visits_user_id ON visit.visits USING btree (user_id);
CREATE INDEX idx_visits_owner_id ON visit.visits USING btree (owner_id);
CREATE INDEX idx_visits_pricing_id ON visit.visits USING btree (pricing_id);
CREATE INDEX idx_visits_status_id ON visit.visits USING btree (status_id);
