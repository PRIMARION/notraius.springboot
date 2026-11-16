-- ── Engagement signals ──────────────────────────────────────────────────────
-- One carrier for the affinity engine, added to a table V45 created:
--
--   impression.comments.vector  the estate's search vector — the same
--                               trigger-owned tsvector every host keeps on
--                               its prose (V35's law, applied to comments).
--                               The Comment entity carries no vector field,
--                               deliberately; reads go through
--                               `vector @@ plainto_tsquery('english', :search)`.
--
-- The reaction weight is not here: it is the score column of
-- impression.reaction_types (V45), on a 1–100 scale with 50 neutral. Reactions
-- themselves hold no score, so there is nothing to freeze per row.

ALTER TABLE impression.comments ADD COLUMN IF NOT EXISTS vector tsvector;

CREATE OR REPLACE FUNCTION public.tsvector_update_comments() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    NEW.vector := setweight(to_tsvector('english', COALESCE(NEW.content, '')), 'A');
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS tsvector_update ON impression.comments;
CREATE TRIGGER tsvector_update
    BEFORE INSERT OR UPDATE OF content ON impression.comments
    FOR EACH ROW EXECUTE FUNCTION public.tsvector_update_comments();

CREATE INDEX IF NOT EXISTS idx_comments_vector ON impression.comments USING gin (vector);

-- Rows that predate the column: build their vectors once with the same law.
UPDATE impression.comments SET vector =
        setweight(to_tsvector('english', COALESCE(content, '')), 'A')
WHERE vector IS NULL;
