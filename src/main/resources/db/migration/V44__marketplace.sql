-- V44__marketplace.sql — the marketplace module's own tables.
--
-- Market is imported by this host, so its rows live in this database next to
-- the host's entities/statuses/validity registries (Section 1.1–4.2 of
-- docs/DATABASE.txt). Four schemas, no second dictionary: status_id and
-- validity_id columns point at notraius rows; currency_id and method_id point
-- at pay rows by bare UUID (another service, never a FK).
--
-- Panier = commande: there is no orders table. card.cards + card.items carry
-- the whole purchase cycle.

CREATE SCHEMA IF NOT EXISTS reference;
CREATE SCHEMA IF NOT EXISTS store;
CREATE SCHEMA IF NOT EXISTS variant;
CREATE SCHEMA IF NOT EXISTS card;

-- ── reference ───────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS reference.categories (
    id          uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at  timestamp with time zone DEFAULT now() NOT NULL,
    updated_at  timestamp with time zone DEFAULT now() NOT NULL,
    parent_id   uuid,
    code        varchar(50) NOT NULL,
    label       varchar(100) NOT NULL,
    CONSTRAINT categories_pkey PRIMARY KEY (id),
    CONSTRAINT uk_categories_code UNIQUE (code),
    CONSTRAINT fk_categories_parent FOREIGN KEY (parent_id) REFERENCES reference.categories (id)
);
CREATE INDEX IF NOT EXISTS idx_categories_parent ON reference.categories (parent_id);

CREATE TABLE IF NOT EXISTS reference.specs (
    id          uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at  timestamp with time zone DEFAULT now() NOT NULL,
    updated_at  timestamp with time zone DEFAULT now() NOT NULL,
    spec_group  varchar(50),
    type        varchar(20) NOT NULL,
    label       varchar(100) NOT NULL,
    data_type   varchar(20) NOT NULL,
    status_id   uuid,
    user_id     uuid,
    CONSTRAINT specs_pkey PRIMARY KEY (id),
    CONSTRAINT uk_specs_group_label UNIQUE (spec_group, label)
);
CREATE INDEX IF NOT EXISTS idx_specs_group ON reference.specs (spec_group);
CREATE INDEX IF NOT EXISTS idx_specs_user ON reference.specs (user_id);

CREATE TABLE IF NOT EXISTS reference.discounts (
    id          uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at  timestamp with time zone DEFAULT now() NOT NULL,
    updated_at  timestamp with time zone DEFAULT now() NOT NULL,
    user_id     uuid,
    visibility  varchar(20) NOT NULL,
    title       varchar(100) NOT NULL,
    description text,
    min_amount  numeric(15,2),
    max_amount  numeric(15,2),
    type        varchar(20) NOT NULL,
    version     integer DEFAULT 1 NOT NULL,
    CONSTRAINT discounts_pkey PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_discounts_user ON reference.discounts (user_id);

-- ── store ───────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS store.stores (
    id          uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at  timestamp with time zone DEFAULT now() NOT NULL,
    updated_at  timestamp with time zone DEFAULT now() NOT NULL,
    user_id     uuid NOT NULL,
    title       varchar(255) NOT NULL,
    description text,
    currency_id uuid,
    status_id   uuid NOT NULL,
    slug        varchar(255) NOT NULL,
    CONSTRAINT stores_pkey PRIMARY KEY (id),
    CONSTRAINT uk_stores_slug UNIQUE (slug)
);
CREATE INDEX IF NOT EXISTS idx_stores_user ON store.stores (user_id);

CREATE TABLE IF NOT EXISTS store.social (
    id          uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at  timestamp with time zone DEFAULT now() NOT NULL,
    updated_at  timestamp with time zone DEFAULT now() NOT NULL,
    store_id    uuid NOT NULL,
    type_id     uuid,
    url         varchar(255) NOT NULL,
    metadata    jsonb,
    CONSTRAINT social_pkey PRIMARY KEY (id),
    CONSTRAINT fk_social_store FOREIGN KEY (store_id) REFERENCES store.stores (id)
);
CREATE INDEX IF NOT EXISTS idx_social_store ON store.social (store_id);

CREATE TABLE IF NOT EXISTS store.rails (
    id          uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at  timestamp with time zone DEFAULT now() NOT NULL,
    updated_at  timestamp with time zone DEFAULT now() NOT NULL,
    store_id    uuid NOT NULL,
    method_id   uuid NOT NULL,
    status_id   uuid NOT NULL,
    number      text NOT NULL,
    username    text,
    CONSTRAINT rails_pkey PRIMARY KEY (id),
    CONSTRAINT uk_rails_store_method_number UNIQUE (store_id, method_id, number),
    CONSTRAINT fk_rails_store FOREIGN KEY (store_id) REFERENCES store.stores (id)
);
CREATE INDEX IF NOT EXISTS idx_rails_store ON store.rails (store_id);
CREATE INDEX IF NOT EXISTS idx_rails_store_status ON store.rails (store_id, status_id);

-- ── variant ─────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS variant.products (
    id          uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at  timestamp with time zone DEFAULT now() NOT NULL,
    updated_at  timestamp with time zone DEFAULT now() NOT NULL,
    store_id    uuid NOT NULL,
    category_id uuid,
    type        varchar(20) NOT NULL,
    label       varchar(255) NOT NULL,
    description text,
    status_id   uuid NOT NULL,
    vector      tsvector,
    CONSTRAINT products_pkey PRIMARY KEY (id),
    CONSTRAINT fk_products_store FOREIGN KEY (store_id) REFERENCES store.stores (id),
    CONSTRAINT fk_products_category FOREIGN KEY (category_id) REFERENCES reference.categories (id)
);
CREATE INDEX IF NOT EXISTS idx_products_store ON variant.products (store_id);
CREATE INDEX IF NOT EXISTS idx_products_category ON variant.products (category_id);
CREATE INDEX IF NOT EXISTS idx_products_search ON variant.products USING gin (vector);

CREATE OR REPLACE FUNCTION public.tsvector_update_market_products() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.vector :=
        setweight(to_tsvector('english', COALESCE(NEW.label, '')), 'A') ||
        setweight(to_tsvector('english', COALESCE(NEW.description, '')), 'B');
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS tsvector_update ON variant.products;
CREATE TRIGGER tsvector_update
    BEFORE INSERT OR UPDATE ON variant.products
    FOR EACH ROW EXECUTE FUNCTION public.tsvector_update_market_products();

CREATE TABLE IF NOT EXISTS variant.variants (
    id          uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at  timestamp with time zone DEFAULT now() NOT NULL,
    updated_at  timestamp with time zone DEFAULT now() NOT NULL,
    product_id  uuid NOT NULL,
    sku         varchar(100) NOT NULL,
    stock       integer DEFAULT 0 NOT NULL,
    price       numeric(15,2) NOT NULL,
    status_id   uuid NOT NULL,
    CONSTRAINT variants_pkey PRIMARY KEY (id),
    CONSTRAINT uk_variants_sku UNIQUE (sku),
    CONSTRAINT fk_variants_product FOREIGN KEY (product_id) REFERENCES variant.products (id)
);
CREATE INDEX IF NOT EXISTS idx_variants_product ON variant.variants (product_id);

CREATE TABLE IF NOT EXISTS variant.attributes (
    id          uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at  timestamp with time zone DEFAULT now() NOT NULL,
    updated_at  timestamp with time zone DEFAULT now() NOT NULL,
    spec_id     uuid NOT NULL,
    variant_id  uuid NOT NULL,
    value       varchar(100) NOT NULL,
    status_id   uuid,
    CONSTRAINT attributes_pkey PRIMARY KEY (id),
    CONSTRAINT uk_attributes_variant_spec UNIQUE (variant_id, spec_id),
    CONSTRAINT fk_attributes_spec FOREIGN KEY (spec_id) REFERENCES reference.specs (id),
    CONSTRAINT fk_attributes_variant FOREIGN KEY (variant_id) REFERENCES variant.variants (id)
);
CREATE INDEX IF NOT EXISTS idx_attributes_spec ON variant.attributes (spec_id);

CREATE TABLE IF NOT EXISTS variant.characteristics (
    id          uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at  timestamp with time zone DEFAULT now() NOT NULL,
    updated_at  timestamp with time zone DEFAULT now() NOT NULL,
    spec_id     uuid NOT NULL,
    variant_id  uuid NOT NULL,
    value       varchar(100) NOT NULL,
    price       numeric(15,2) NOT NULL,
    sequence    integer NOT NULL,
    status_id   uuid,
    stocks      integer DEFAULT 0 NOT NULL,
    max         integer DEFAULT 1 NOT NULL,
    CONSTRAINT characteristics_pkey PRIMARY KEY (id),
    CONSTRAINT uk_characteristics_variant_spec UNIQUE (variant_id, spec_id),
    CONSTRAINT fk_characteristics_spec FOREIGN KEY (spec_id) REFERENCES reference.specs (id),
    CONSTRAINT fk_characteristics_variant FOREIGN KEY (variant_id) REFERENCES variant.variants (id)
);
CREATE INDEX IF NOT EXISTS idx_characteristics_variant ON variant.characteristics (variant_id);

CREATE TABLE IF NOT EXISTS variant.discount (
    id               uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at       timestamp with time zone DEFAULT now() NOT NULL,
    updated_at       timestamp with time zone DEFAULT now() NOT NULL,
    discount_id      uuid NOT NULL,
    discount_version integer NOT NULL,
    variant_id       uuid NOT NULL,
    value            numeric(15,2) NOT NULL,
    validity_id      uuid,
    CONSTRAINT discount_pkey PRIMARY KEY (id),
    CONSTRAINT fk_discount_template FOREIGN KEY (discount_id) REFERENCES reference.discounts (id),
    CONSTRAINT fk_discount_variant FOREIGN KEY (variant_id) REFERENCES variant.variants (id)
);
CREATE INDEX IF NOT EXISTS idx_discount_variant ON variant.discount (variant_id);

-- ── card (panier = commande) ────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS card.cards (
    id          uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at  timestamp with time zone DEFAULT now() NOT NULL,
    updated_at  timestamp with time zone DEFAULT now() NOT NULL,
    user_id     uuid NOT NULL,
    status_id   uuid NOT NULL,
    validity_id uuid,
    CONSTRAINT cards_pkey PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_cards_user_status ON card.cards (user_id, status_id);

CREATE TABLE IF NOT EXISTS card.items (
    id           uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at   timestamp with time zone DEFAULT now() NOT NULL,
    updated_at   timestamp with time zone DEFAULT now() NOT NULL,
    card_id      uuid NOT NULL,
    entity_id    uuid,
    reference_id uuid,
    currency_id  uuid,
    quantity     integer DEFAULT 1 NOT NULL,
    amount       numeric(15,2) NOT NULL,
    discount     numeric(15,2) NOT NULL,
    total_amount numeric(15,2) NOT NULL,
    validity_id  uuid,
    status_id    uuid NOT NULL,
    metadata     jsonb,
    committed_at timestamp with time zone,
    CONSTRAINT items_pkey PRIMARY KEY (id),
    CONSTRAINT fk_items_card FOREIGN KEY (card_id) REFERENCES card.cards (id)
);
CREATE INDEX IF NOT EXISTS idx_items_card ON card.items (card_id);
CREATE INDEX IF NOT EXISTS idx_items_card_status ON card.items (card_id, status_id);
CREATE INDEX IF NOT EXISTS idx_items_reference ON card.items (reference_id);

-- ── the host dictionary market resolves ─────────────────────────────────

INSERT INTO notraius.entities (id, created_at, updated_at, code)
SELECT gen_random_uuid(), now(), now(), v.code
FROM (VALUES
    ('PRODUCT'), ('VARIANT'), ('SPEC'), ('STORE'), ('CATEGORY'),
    ('DISCOUNT'), ('SOCIAL'), ('RAIL'), ('CARD'), ('CARD_ITEM'),
    ('MARKET_VALIDITY')
) AS v(code)
WHERE NOT EXISTS (SELECT 1 FROM notraius.entities e WHERE e.code = v.code);

INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
SELECT gen_random_uuid(), now(), now(), v.code, v.description, e.id
FROM (VALUES
    -- store lifecycle
    ('STORE', 'PENDING', 'Awaiting moderation'),
    ('STORE', 'ACTIVE', 'Shopfront live'),
    ('STORE', 'SUSPENDED', 'Shopfront suspended'),
    ('STORE', 'REJECTED', 'Store rejected'),
    -- catalogue
    ('PRODUCT', 'DRAFT', 'Product draft'),
    ('PRODUCT', 'PUBLISHED', 'Product published'),
    ('PRODUCT', 'ARCHIVED', 'Product archived'),
    ('VARIANT', 'DRAFT', 'Variant draft'),
    ('VARIANT', 'ACTIVE', 'Variant sellable'),
    ('VARIANT', 'ARCHIVED', 'Variant archived'),
    -- reference data
    ('SPEC', 'PENDING', 'Spec proposed'),
    ('SPEC', 'ACTIVE', 'Spec approved'),
    ('SPEC', 'REJECTED', 'Spec refused'),
    ('CATEGORY', 'ACTIVE', 'Category active'),
    ('DISCOUNT', 'DRAFT', 'Template draft'),
    ('DISCOUNT', 'ACTIVE', 'Template active'),
    ('DISCOUNT', 'ARCHIVED', 'Template archived'),
    ('SOCIAL', 'ACTIVE', 'Social link active'),
    -- rails
    ('RAIL', 'PENDING', 'Rail declared'),
    ('RAIL', 'ACTIVE', 'Rail accepting'),
    ('RAIL', 'DISABLED', 'Rail retired'),
    ('RAIL', 'REJECTED', 'Rail abandoned'),
    -- the order cycle
    ('CARD', 'DRAFT', 'Cart open'),
    ('CARD', 'REQUESTED', 'Order requested'),
    ('CARD', 'ACCEPTED', 'Order accepted'),
    ('CARD', 'COMPLETED', 'Order completed'),
    ('CARD', 'CANCELLED', 'Order cancelled'),
    ('CARD', 'EXPIRED', 'Order expired'),
    ('CARD', 'REFUNDED', 'Order refunded'),
    ('CARD_ITEM', 'REQUESTED', 'Line requested'),
    ('CARD_ITEM', 'PENDING', 'Cash line awaiting payment'),
    ('CARD_ITEM', 'ACCEPTED', 'Line accepted'),
    ('CARD_ITEM', 'PAID', 'Cash payment declared'),
    ('CARD_ITEM', 'COMPLETED', 'Line completed'),
    ('CARD_ITEM', 'CANCELLED', 'Line cancelled'),
    ('CARD_ITEM', 'REFUNDED', 'Line refunded'),
    -- validity words for market-opened windows
    ('MARKET_VALIDITY', 'ACTIVE', 'Window live'),
    ('MARKET_VALIDITY', 'DISABLED', 'Window retired')
) AS v(entity_code, code, description)
JOIN notraius.entities e ON e.code = v.entity_code
WHERE NOT EXISTS (
    SELECT 1 FROM notraius.statuses s
    WHERE s.code = v.code AND s.entity_id = e.id
);
