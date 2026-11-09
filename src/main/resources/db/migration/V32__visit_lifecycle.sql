-- PLAN.md 5.3–5.4: the visit's own life after CONFIRMED.
--
-- The audit log remains the evidence (who claimed, who countersigned, at what
-- instant), but the sweeps need an index they can query without reading the
-- partitioned log for every row: these columns are that index, written in the
-- same transaction as the evidence entry. A column and a log entry therefore
-- never disagree — they are the same act recorded twice for two readers.
ALTER TABLE visit.visits
    ADD COLUMN IF NOT EXISTS started_at          timestamptz,
    ADD COLUMN IF NOT EXISTS started_by          uuid,
    ADD COLUMN IF NOT EXISTS start_confirmed_at  timestamptz,
    ADD COLUMN IF NOT EXISTS start_confirmed_by  uuid,
    ADD COLUMN IF NOT EXISTS completed_at        timestamptz;

COMMENT ON COLUMN visit.visits.started_at IS
    'The STARTED claim instant (PLAN 5.3) — the 10-minute counter-signature window opens here.';
COMMENT ON COLUMN visit.visits.start_confirmed_at IS
    'The countersignature instant; null past the window means the claim lapsed and the no-show laws fire.';
COMMENT ON COLUMN visit.visits.completed_at IS
    'When the visit reached COMPLETED — by the mutual confirm or by the hour, never both.';

-- The three sweeps: an unconfirmed claim, a confirmed visit that was never
-- claimed, and a started visit whose assessment hour has passed.
CREATE INDEX IF NOT EXISTS idx_visits_status_started_at
    ON visit.visits USING btree (status_id, started_at);
