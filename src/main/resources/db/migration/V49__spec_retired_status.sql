-- ── SPEC.RETIRED — the cascade's vocabulary row ─────────────────────────────
-- A type retirement cascades to the specs filed under it (the shared
-- reference module's branch walk). Those specs are not refused data (REJECTED
-- is a moderation verdict) and not pending proposals; they are catalogue rows
-- withdrawn from selection while products that already carry them keep
-- resolving. That needs its own word: RETIRED.
-- The management API refuses to invent status rows — a missing status is a
-- configuration error, never a silent default — so the word is seeded here,
-- exactly as the market hosts seed theirs.
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
SELECT gen_random_uuid(), now(), now(), 'RETIRED',
       'Withdrawn from selection; stored references keep resolving', e.id
FROM notraius.entities e
WHERE e.code = 'SPEC'
  AND NOT EXISTS (SELECT 1 FROM notraius.statuses s
                  WHERE s.entity_id = e.id AND s.code = 'RETIRED');
