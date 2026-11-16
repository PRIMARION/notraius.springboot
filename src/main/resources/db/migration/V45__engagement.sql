-- ── Engagement on notarius ──────────────────────────────────────────────────
-- The shared engagement module maps these tables; notarius owns the DDL.
--
--   impression.impressions   one actor/row relationship (never one per view)
--   impression.states        the visit timeline; `when` is the validity window
--   impression.reaction_types  the weight (1–100) of each REACTIONS type
--   impression.reactions     live type per impression, swaps kept as history
--   impression.comments      thread attached to the commenter's impression
--   collection.collections   actor-curated lists
--   collection.collection_items
--   preference.affinities    user_id NULL = deployment default, admin-edited
--
-- Liveness of a state or reaction is the host validity row (notraius.validity);
-- the module references it by UUID through ValidityPort, exactly as the event
-- and subscription modules do. Two entity codes namespace the statuses those
-- validity rows resolve against.

CREATE SCHEMA IF NOT EXISTS impression;
CREATE SCHEMA IF NOT EXISTS collection;
CREATE SCHEMA IF NOT EXISTS preference;


-- ── 1. Lifecycle vocabulary ──────────────────────────────────────────────────
-- IMPRESSION_STATES: ACTIVE while a visit is open, CLOSED once bounded.
-- REACTIONS:         ACTIVE while the reaction is current, REVOKED after a swap
--                    or a withdrawal. The rows themselves stay as history.

INSERT INTO notraius.entities (id, created_at, updated_at, code)
SELECT v.id, now(), now(), v.code
FROM (VALUES
        ('c0000000-0000-0000-0000-000000000001'::uuid, 'IMPRESSION_STATES'),
        ('c0000000-0000-0000-0000-000000000002'::uuid, 'REACTIONS'),
        ('c0000000-0000-0000-0000-000000000003'::uuid, 'COMMENTS'),
        ('c0000000-0000-0000-0000-000000000004'::uuid, 'COLLECTIONS'),
        ('c0000000-0000-0000-0000-000000000005'::uuid, 'COLLECTION_ITEMS')
     ) AS v(id, code)
WHERE NOT EXISTS (SELECT 1 FROM notraius.entities e WHERE e.code = v.code);

INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
SELECT v.id, now(), now(), v.code, v.description, e.id
FROM notraius.entities e
JOIN (VALUES
        ('c0000000-0000-0000-0000-0000000000a1'::uuid, 'IMPRESSION_STATES', 'ACTIVE',  'A visit is open; its validity window is still running'),
        ('c0000000-0000-0000-0000-0000000000a2'::uuid, 'IMPRESSION_STATES', 'CLOSED',  'The visit ended; the validity window records when'),
        ('c0000000-0000-0000-0000-0000000000a3'::uuid, 'REACTIONS',         'ACTIVE',  'The current reaction on the actor''s impression'),
        ('c0000000-0000-0000-0000-0000000000a4'::uuid, 'REACTIONS',         'REVOKED', 'Superseded by a swap or withdrawn; kept as history'),
        ('c0000000-0000-0000-0000-0000000000a5'::uuid, 'REACTIONS',         'RETIRED', 'A reaction type withdrawn from the catalogue; stored reactions still resolve'),
        ('c0000000-0000-0000-0000-0000000000b1'::uuid, 'COMMENTS',          'ACTIVE',  'A visible comment'),
        ('c0000000-0000-0000-0000-0000000000b2'::uuid, 'COMMENTS',          'DELETED', 'A comment removed by its author; kept for history'),
        ('c0000000-0000-0000-0000-0000000000b3'::uuid, 'COLLECTIONS',       'ACTIVE',  'A list the owner still keeps'),
        ('c0000000-0000-0000-0000-0000000000b4'::uuid, 'COLLECTIONS',       'DELETED', 'A list soft-deleted by its owner'),
        ('c0000000-0000-0000-0000-0000000000c1'::uuid, 'COLLECTION_ITEMS',  'ACTIVE',  'A saved row still in the list'),
        ('c0000000-0000-0000-0000-0000000000c2'::uuid, 'COLLECTION_ITEMS',  'DELETED', 'A saved row removed from the list; kept for history')
     ) AS v(id, entity_code, code, description) ON v.entity_code = e.code
WHERE NOT EXISTS (SELECT 1 FROM notraius.statuses s
                  WHERE s.entity_id = e.id AND s.code = v.code);


-- ── 2. impression.impressions ────────────────────────────────────────────────
-- The actor is a user when authenticated and a device when not; at least one
-- must be present. The unique expression collapses the two identities into one
-- actor key, so a user opening the same property from a second device converges
-- on the same row instead of forking their history.

CREATE TABLE impression.impressions (
    id           uuid        DEFAULT gen_random_uuid() NOT NULL,
    created_at   timestamptz DEFAULT now() NOT NULL,
    updated_at   timestamptz DEFAULT now() NOT NULL,
    entity_id    uuid        NOT NULL,
    reference_id uuid        NOT NULL,
    user_id      uuid,
    device_id    uuid,
    CONSTRAINT pk_impressions PRIMARY KEY (id),
    CONSTRAINT fk_impressions_entity FOREIGN KEY (entity_id)
        REFERENCES notraius.entities (id),
    CONSTRAINT ck_impressions_actor CHECK (user_id IS NOT NULL OR device_id IS NOT NULL)
);

CREATE UNIQUE INDEX ux_impressions_actor
    ON impression.impressions (entity_id, reference_id,
        COALESCE(user_id, device_id, '00000000-0000-0000-0000-000000000000'::uuid));

CREATE INDEX idx_impressions_entity_reference
    ON impression.impressions (entity_id, reference_id);
CREATE INDEX idx_impressions_user ON impression.impressions (user_id)
    WHERE user_id IS NOT NULL;
CREATE INDEX idx_impressions_device ON impression.impressions (device_id)
    WHERE device_id IS NOT NULL;


-- ── 3. impression.states ────────────────────────────────────────────────────
-- One row per visit, not per day. `duration` is dwell in milliseconds (0 while
-- the visit is open); the start and end instants live in notraius.validity.

CREATE TABLE impression.states (
    id            uuid        DEFAULT gen_random_uuid() NOT NULL,
    created_at    timestamptz DEFAULT now() NOT NULL,
    updated_at    timestamptz DEFAULT now() NOT NULL,
    impression_id uuid        NOT NULL,
    validity_id   uuid        NOT NULL,
    duration      bigint      DEFAULT 0 NOT NULL,
    CONSTRAINT pk_impression_states PRIMARY KEY (id),
    CONSTRAINT fk_impression_states_impression FOREIGN KEY (impression_id)
        REFERENCES impression.impressions (id) ON DELETE CASCADE,
    CONSTRAINT ck_impression_states_duration CHECK (duration >= 0)
);

CREATE INDEX idx_impression_states_impression
    ON impression.states (impression_id);
CREATE INDEX idx_impression_states_validity
    ON impression.states (validity_id);


-- ── 4. impression.reaction_types / impression.reactions ────────────────────
-- A reaction type is a weight, not a vocabulary: the kind itself is a
-- catalogue row under REACTIONS (notraius.types here, moved to config.types by
-- V48), and this table says what that kind is worth. 50 is neutral, so the
-- affinity loop reads (score - 50) / 10; adding a kind or changing its weight
-- is data. The FK means a catalogue row cannot be deleted while its weight
-- exists.
--
-- A reaction points at that row instead of carrying a kind string and a
-- client-supplied score. The impression belongs to the actor, so this is one
-- live reaction per actor per property; a swap closes the old validity as
-- REVOKED and opens a new one.

CREATE TABLE impression.reaction_types (
    id         uuid        DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    type_id    uuid        NOT NULL,
    score      integer     NOT NULL,
    CONSTRAINT pk_reaction_types PRIMARY KEY (id),
    CONSTRAINT uk_reaction_types_type UNIQUE (type_id),
    CONSTRAINT fk_reaction_types_type FOREIGN KEY (type_id)
        REFERENCES notraius.types (id),
    CONSTRAINT ck_reaction_types_score CHECK (score BETWEEN 1 AND 100)
);

CREATE TABLE impression.reactions (
    id            uuid        DEFAULT gen_random_uuid() NOT NULL,
    created_at    timestamptz DEFAULT now() NOT NULL,
    updated_at    timestamptz DEFAULT now() NOT NULL,
    impression_id uuid        NOT NULL,
    type_id       uuid        NOT NULL,
    validity_id   uuid        NOT NULL,
    CONSTRAINT pk_reactions PRIMARY KEY (id),
    CONSTRAINT fk_reactions_impression FOREIGN KEY (impression_id)
        REFERENCES impression.impressions (id) ON DELETE CASCADE,
    CONSTRAINT fk_reactions_type FOREIGN KEY (type_id)
        REFERENCES impression.reaction_types (id)
);

CREATE INDEX idx_reactions_impression ON impression.reactions (impression_id);
CREATE INDEX idx_reactions_type ON impression.reactions (type_id);
CREATE INDEX idx_reactions_validity ON impression.reactions (validity_id);


-- ── 5. impression.comments ──────────────────────────────────────────────────
-- The public thread is every live comment whose impression points at the same
-- (entity_id, reference_id). Parent links form the replies; removal is soft so
-- children of a removed comment stay reachable.

CREATE TABLE impression.comments (
    id            uuid        DEFAULT gen_random_uuid() NOT NULL,
    created_at    timestamptz DEFAULT now() NOT NULL,
    updated_at    timestamptz DEFAULT now() NOT NULL,
    impression_id uuid        NOT NULL,
    comment_id    uuid,
    content       text        NOT NULL,
    status_id     uuid        NOT NULL,
    CONSTRAINT pk_comments PRIMARY KEY (id),
    CONSTRAINT fk_comments_impression FOREIGN KEY (impression_id)
        REFERENCES impression.impressions (id) ON DELETE CASCADE,
    CONSTRAINT fk_comments_parent FOREIGN KEY (comment_id)
        REFERENCES impression.comments (id),
    CONSTRAINT fk_comments_status FOREIGN KEY (status_id)
        REFERENCES notraius.statuses (id),
    CONSTRAINT ck_comments_content CHECK (length(btrim(content)) > 0)
);

CREATE INDEX idx_comments_impression ON impression.comments (impression_id);
CREATE INDEX idx_comments_status ON impression.comments (status_id);
CREATE INDEX idx_comments_parent ON impression.comments (comment_id);


-- ── 6. collection.collections / collection_items ────────────────────────────
-- Actor-local lists that may span entity types; items keep the host entity id.

CREATE TABLE collection.collections (
    id          uuid         DEFAULT gen_random_uuid() NOT NULL,
    created_at  timestamptz  DEFAULT now() NOT NULL,
    updated_at  timestamptz  DEFAULT now() NOT NULL,
    user_id     uuid         NOT NULL,
    label       varchar(120) NOT NULL,
    description varchar(1000),
    status_id   uuid         NOT NULL,
    CONSTRAINT pk_collections PRIMARY KEY (id),
    CONSTRAINT fk_collections_status FOREIGN KEY (status_id)
        REFERENCES notraius.statuses (id),
    CONSTRAINT ck_collections_label CHECK (length(btrim(label)) > 0)
);

CREATE INDEX idx_collections_user ON collection.collections (user_id);
CREATE INDEX idx_collections_status ON collection.collections (status_id);

CREATE TABLE collection.collection_items (
    id            uuid        DEFAULT gen_random_uuid() NOT NULL,
    created_at    timestamptz DEFAULT now() NOT NULL,
    updated_at    timestamptz DEFAULT now() NOT NULL,
    collection_id uuid        NOT NULL,
    entity_id     uuid        NOT NULL,
    reference_id  uuid        NOT NULL,
    note          varchar(1000),
    status_id     uuid        NOT NULL,
    CONSTRAINT pk_collection_items PRIMARY KEY (id),
    CONSTRAINT fk_collection_items_collection FOREIGN KEY (collection_id)
        REFERENCES collection.collections (id) ON DELETE CASCADE,
    CONSTRAINT fk_collection_items_entity FOREIGN KEY (entity_id)
        REFERENCES notraius.entities (id),
    CONSTRAINT fk_collection_items_status FOREIGN KEY (status_id)
        REFERENCES notraius.statuses (id)
);

CREATE INDEX idx_collection_items_collection
    ON collection.collection_items (collection_id);
CREATE INDEX idx_collection_items_reference
    ON collection.collection_items (entity_id, reference_id);
CREATE INDEX idx_collection_items_status
    ON collection.collection_items (status_id);
CREATE UNIQUE INDEX ux_collection_items_live
    ON collection.collection_items (collection_id, entity_id, reference_id)
    WHERE status_id = 'c0000000-0000-0000-0000-0000000000c1';


-- ── 7. preference.affinities ────────────────────────────────────────────────
-- One table, no feed_defaults. A row with a null user_id is the deployment
-- default (administration only); a user row shadows it for that type. 1.0 is
-- neutral, so a fresh deployment ranks without seeds.

CREATE TABLE preference.affinities (
    id         uuid         DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamptz  DEFAULT now() NOT NULL,
    updated_at timestamptz  DEFAULT now() NOT NULL,
    user_id    uuid,
    entity_id  uuid         NOT NULL,
    score      double precision DEFAULT 1.0 NOT NULL,
    CONSTRAINT pk_affinities PRIMARY KEY (id),
    CONSTRAINT fk_affinities_entity FOREIGN KEY (entity_id)
        REFERENCES notraius.entities (id),
    CONSTRAINT ck_affinities_score CHECK (score >= 0 AND score <= 10)
);

CREATE UNIQUE INDEX ux_affinities_actor
    ON preference.affinities (entity_id,
        COALESCE(user_id, '00000000-0000-0000-0000-000000000000'::uuid));
CREATE INDEX idx_affinities_user ON preference.affinities (user_id)
    WHERE user_id IS NOT NULL;
