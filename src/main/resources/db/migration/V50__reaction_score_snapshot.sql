-- A reaction type's weight is now editable. The affinity ledger must not move
-- retroactively when an operator retunes a kind, so the weight is frozen on the
-- reaction row at the moment the act is recorded:
--
--   • a fresh reaction nudges by its snapshot;
--   • a swap moves by (new snapshot − old snapshot);
--   • a withdrawal reverses the snapshot it was recorded with.
--
-- Existing rows are backfilled from the weight they were recorded under (the
-- only value available). From then on, changing impression.reaction_types.score
-- affects future reactions only.
ALTER TABLE impression.reactions ADD COLUMN IF NOT EXISTS score integer;

UPDATE impression.reactions r
   SET score = t.score
  FROM impression.reaction_types t
 WHERE r.type_id = t.id
   AND r.score IS NULL;

ALTER TABLE impression.reactions ALTER COLUMN score SET NOT NULL;

ALTER TABLE impression.reactions
    ADD CONSTRAINT ck_reactions_score CHECK (score BETWEEN 1 AND 100);
