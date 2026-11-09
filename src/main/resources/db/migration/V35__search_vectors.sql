-- V35__search_vectors.sql — the search columns for the domain's prose.
--
-- The mechanism is the estate's own, not a new one: every searchable table
-- carries a `vector tsvector` column that a BEFORE INSERT OR UPDATE trigger
-- rebuilds from its prose, a GIN index on that column, and every search door
-- asks `vector @@ plainto_tsquery('english', :search)`. `V1__entities.sql`
-- already does exactly this for `communication.chat_requests`,
-- `communication.chats` and `communication.messages`, and the contract module
-- does it for its type/contract/term tables — this migration extends the same
-- pattern to the notraius domain's own prose and changes nothing about how it
-- works.
--
-- Why a trigger instead of the application: the index can never lag the row.
-- An edit that renames a title cannot forget to rebuild the vector, a bulk
-- write cannot skip it, and a data fix run with psql keeps the catalogue
-- searchable. There is no indexer, no backfill job, no provider and no key —
-- the database keeps its own index as it keeps its own constraints.
--
-- The weights are Postgres' own relevance dial (A is the strongest):
--
--   • `property.properties`  title A, description B
--   • `notraius.rules`       title A, description B
--   • `notraius.types`       code A, label B, description C
--   • `notraius.policies`    code A, title B, description C
--   • `notraius.amenities`   code A, label B, description C
--
-- These are the five tables a human browses by words: the market's listings,
-- the house rules, the type catalogue, the admin standards and the amenity
-- catalogue — each one already has a list door with a `search` parameter that
-- this column now answers.
--
-- Deliberately NOT vectorised: `property.pricings`, `property.policies`,
-- `property.rules`, `property.amenities`, `visit.*`, `lease.*` and
-- `subscription.*`. They hold numbers, dates, parties and ids joined to a row
-- whose prose is already indexed; a name lives in one place, and a search that
-- hits a pricing row would have to fetch its parent anyway. Their filters are
-- equality filters that the btree indexes of V28–V34 already answer.
--
-- `english` is the configuration the estate's base schema already uses
-- (V1's three functions); keeping one configuration keeps `ts_rank` and
-- `plainto_tsquery` comparable across the whole database. A second language is
-- a future migration with a per-row configuration column, never a silent
-- change of this one.

-- ── property.properties — the market's own text ────────────────────────────

CREATE OR REPLACE FUNCTION public.tsvector_update_properties() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.vector :=
        setweight(to_tsvector('english', COALESCE(NEW.title, '')), 'A') ||
        setweight(to_tsvector('english', COALESCE(NEW.description, '')), 'B');
    RETURN NEW;
END;
$$;

ALTER TABLE property.properties ADD COLUMN IF NOT EXISTS vector tsvector;

CREATE INDEX IF NOT EXISTS idx_properties_search
    ON property.properties USING gin (vector);

CREATE TRIGGER tsvector_update
    BEFORE INSERT OR UPDATE ON property.properties
    FOR EACH ROW EXECUTE FUNCTION public.tsvector_update_properties();

-- Rows that predate the column: build their vectors once with the same
-- expression the trigger will use from now on. (The trigger fires on this
-- UPDATE too; the value is identical.)
UPDATE property.properties
SET vector =
        setweight(to_tsvector('english', COALESCE(title, '')), 'A') ||
        setweight(to_tsvector('english', COALESCE(description, '')), 'B');

-- ── notraius.rules — house rules are written in sentences ──────────────────

CREATE OR REPLACE FUNCTION public.tsvector_update_rules() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.vector :=
        setweight(to_tsvector('english', COALESCE(NEW.title, '')), 'A') ||
        setweight(to_tsvector('english', COALESCE(NEW.description, '')), 'B');
    RETURN NEW;
END;
$$;

ALTER TABLE notraius.rules ADD COLUMN IF NOT EXISTS vector tsvector;

CREATE INDEX IF NOT EXISTS idx_rules_search
    ON notraius.rules USING gin (vector);

CREATE TRIGGER tsvector_update
    BEFORE INSERT OR UPDATE ON notraius.rules
    FOR EACH ROW EXECUTE FUNCTION public.tsvector_update_rules();

UPDATE notraius.rules
SET vector =
        setweight(to_tsvector('english', COALESCE(title, '')), 'A') ||
        setweight(to_tsvector('english', COALESCE(description, '')), 'B');

-- ── notraius.types — the catalogue's code, label and description ───────────

CREATE OR REPLACE FUNCTION public.tsvector_update_types() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.vector :=
        setweight(to_tsvector('english', COALESCE(NEW.code, '')), 'A') ||
        setweight(to_tsvector('english', COALESCE(NEW.label, '')), 'B') ||
        setweight(to_tsvector('english', COALESCE(NEW.description, '')), 'C');
    RETURN NEW;
END;
$$;

ALTER TABLE notraius.types ADD COLUMN IF NOT EXISTS vector tsvector;

CREATE INDEX IF NOT EXISTS idx_types_search
    ON notraius.types USING gin (vector);

CREATE TRIGGER tsvector_update
    BEFORE INSERT OR UPDATE ON notraius.types
    FOR EACH ROW EXECUTE FUNCTION public.tsvector_update_types();

UPDATE notraius.types
SET vector =
        setweight(to_tsvector('english', COALESCE(code, '')), 'A') ||
        setweight(to_tsvector('english', COALESCE(label, '')), 'B') ||
        setweight(to_tsvector('english', COALESCE(description, '')), 'C');

-- ── notraius.policies — the admin standards ────────────────────────────────

CREATE OR REPLACE FUNCTION public.tsvector_update_policies() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.vector :=
        setweight(to_tsvector('english', COALESCE(NEW.code, '')), 'A') ||
        setweight(to_tsvector('english', COALESCE(NEW.title, '')), 'B') ||
        setweight(to_tsvector('english', COALESCE(NEW.description, '')), 'C');
    RETURN NEW;
END;
$$;

ALTER TABLE notraius.policies ADD COLUMN IF NOT EXISTS vector tsvector;

CREATE INDEX IF NOT EXISTS idx_policies_search
    ON notraius.policies USING gin (vector);

CREATE TRIGGER tsvector_update
    BEFORE INSERT OR UPDATE ON notraius.policies
    FOR EACH ROW EXECUTE FUNCTION public.tsvector_update_policies();

UPDATE notraius.policies
SET vector =
        setweight(to_tsvector('english', COALESCE(code, '')), 'A') ||
        setweight(to_tsvector('english', COALESCE(title, '')), 'B') ||
        setweight(to_tsvector('english', COALESCE(description, '')), 'C');

-- ── notraius.amenities — the moderated catalogue ───────────────────────────

CREATE OR REPLACE FUNCTION public.tsvector_update_amenities() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.vector :=
        setweight(to_tsvector('english', COALESCE(NEW.code, '')), 'A') ||
        setweight(to_tsvector('english', COALESCE(NEW.label, '')), 'B') ||
        setweight(to_tsvector('english', COALESCE(NEW.description, '')), 'C');
    RETURN NEW;
END;
$$;

ALTER TABLE notraius.amenities ADD COLUMN IF NOT EXISTS vector tsvector;

CREATE INDEX IF NOT EXISTS idx_amenities_search
    ON notraius.amenities USING gin (vector);

CREATE TRIGGER tsvector_update
    BEFORE INSERT OR UPDATE ON notraius.amenities
    FOR EACH ROW EXECUTE FUNCTION public.tsvector_update_amenities();

UPDATE notraius.amenities
SET vector =
        setweight(to_tsvector('english', COALESCE(code, '')), 'A') ||
        setweight(to_tsvector('english', COALESCE(label, '')), 'B') ||
        setweight(to_tsvector('english', COALESCE(description, '')), 'C');

-- ── the predicate the application asks ────────────────────────────────────
-- HQL cannot write {@code @@}, and a native query would lose the entity graph,
-- the count query and the sort that a page needs. So the operator is wrapped
-- in one small function the repositories call by name
-- (`NotraiusSqlFunctions` registers it with Hibernate as a Boolean-returning
-- function).
--
-- It is plain SQL — not plpgsql — and IMMUTABLE, which is what lets the planner
-- inline it: the query plan still shows a Bitmap Index Scan on
-- idx_<table>_search, so the wrapper costs a function call and nothing else.
-- `plainto_tsquery` is the forgiving form on purpose: a user types
-- "waterfront rooms", not a tsquery expression, and text that is not a query
-- syntax must be a search term, never a syntax error.

CREATE OR REPLACE FUNCTION public.tsvector_matches(vector tsvector, query text)
    RETURNS boolean
    LANGUAGE sql
    IMMUTABLE
    PARALLEL SAFE
    AS $$
        SELECT $1 @@ plainto_tsquery('english', $2)
    $$;

COMMENT ON FUNCTION public.tsvector_matches(tsvector, text) IS
    'V35: the search predicate of every vectorised table — a plain-to-tsquery match, inlined by the planner so the GIN index answers it.';
