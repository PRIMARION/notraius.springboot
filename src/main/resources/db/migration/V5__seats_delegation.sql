-- UPGRADE seats (UPGRADE.md) — délégation d'entreprise à grande échelle.
-- Même contenu que primarion V6 (registry locale notraius.entities/notraius.statuses).

-- 1) Purge AVANT le resserrement
DELETE FROM access.consents WHERE entity IN ('USER', 'SERVICE');

-- 2) Resserrement
ALTER TABLE access.consents ALTER COLUMN reference_id SET NOT NULL;
ALTER TABLE access.consents DROP CONSTRAINT IF EXISTS chk_consents_entity;
ALTER TABLE access.consents ADD CONSTRAINT chk_consents_entity
    CHECK (entity IN ('APP', 'SEAT'));

-- 3) Registry SEATS
INSERT INTO notraius.entities (id, created_at, updated_at, code)
VALUES (gen_random_uuid(), now(), now(), 'SEATS');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), now(), now(), 'ACTIVE', 'Seat is active and delegating', (SELECT id FROM notraius.entities WHERE code = 'SEATS'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), now(), now(), 'SUSPENDED', 'Seat suspended — every delegation on it is dead', (SELECT id FROM notraius.entities WHERE code = 'SEATS'));

-- 4) access.seats
CREATE TABLE access.seats (
    id          uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at  timestamp with time zone DEFAULT now() NOT NULL,
    updated_at  timestamp with time zone DEFAULT now() NOT NULL,
    created_by  uuid NOT NULL,
    code        character varying(60) NOT NULL,
    name        character varying(120) NOT NULL,
    description text,
    status_id   uuid NOT NULL,
    CONSTRAINT seats_pkey PRIMARY KEY (id),
    CONSTRAINT uk_seats_owner_code UNIQUE (created_by, code)
);
CREATE INDEX idx_seats_owner ON access.seats USING btree (created_by);

-- 5) access.seat_assignments
CREATE TABLE access.seat_assignments (
    id          uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at  timestamp with time zone DEFAULT now() NOT NULL,
    updated_at  timestamp with time zone DEFAULT now() NOT NULL,
    created_by  uuid NOT NULL,
    seat_id     uuid NOT NULL,
    user_id     uuid NOT NULL,
    validity_id uuid NOT NULL,
    CONSTRAINT seat_assignments_pkey PRIMARY KEY (id),
    CONSTRAINT uk_seat_assignment UNIQUE (seat_id, user_id),
    CONSTRAINT fk_seat_assignment_seat FOREIGN KEY (seat_id) REFERENCES access.seats (id)
);
CREATE INDEX idx_seat_assignments_user ON access.seat_assignments USING btree (user_id);
CREATE INDEX idx_seat_assignments_seat ON access.seat_assignments USING btree (seat_id);
