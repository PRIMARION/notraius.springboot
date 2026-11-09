-- ═══════════════════════════════════════════════════════════════════════════
-- V36 — the sweeps' ledger, and the indexes their keyset pages read by
-- ═══════════════════════════════════════════════════════════════════════════
--
-- The four clocks of Parts 5 and 6 (visit expiry, visit outcome, lease expiry,
-- the lease vigil) used to read their whole worklist into memory, one row per
-- visit or lease, in one transaction. That is fine for a seed database and
-- wrong for the table sizes this domain is built for: ten million visits or
-- leases is an out-of-memory, and a single transaction over a million rows
-- holds locks for the length of the pass.
--
-- Each pass now reads a PAGE (a bounded keyset slice, `app.notraius.sweep-page-
-- size` rows) and settles the rows one at a time, each in its own transaction.
-- A pass that runs out of budget stops cleanly and the NEXT pass resumes from
-- where this one stopped — that is what this table stores: one row per job,
-- carrying the (time, id) cursor of the last row the pass got through, plus two
-- counters an operator can read to prove the clock actually ran.
--
-- The cursor is not correctness. It is the promise that a ten-million-row
-- worklist makes forward progress across ticks instead of re-reading its first
-- page forever. When a pass exhausts its worklist the cursor is reset, so the
-- next pass starts at the beginning again and every row the predicate still
-- matches — including rows a previous pass failed on — is looked at afresh.
--
-- Rows are keyed by (time column, id): the time column is the one the clock
-- reads (created_at, deadline, validity end, claim instant), and id is the
-- tiebreak that makes the order total, so two rows sharing an instant can never
-- be skipped or read twice at a page seam.

CREATE TABLE IF NOT EXISTS notraius.sweep_cursors (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    job          varchar(64) NOT NULL,
    cursor_key   timestamptz NOT NULL,
    cursor_id    uuid        NOT NULL,
    rows_touched bigint      NOT NULL DEFAULT 0,
    passes       bigint      NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_sweep_cursors_job UNIQUE (job),
    CONSTRAINT ck_sweep_cursors_rows_non_negative CHECK (rows_touched >= 0),
    CONSTRAINT ck_sweep_cursors_passes_non_negative CHECK (passes >= 0)
);

COMMENT ON TABLE notraius.sweep_cursors IS
    'One row per scheduled sweep: the (time, id) cursor the last pass stopped at, and how many rows and passes it has done.';
COMMENT ON COLUMN notraius.sweep_cursors.cursor_key IS
    'The time half of the keyset cursor: the value of the sweep''s own clock column on the last row the pass settled.';
COMMENT ON COLUMN notraius.sweep_cursors.cursor_id IS
    'The id half of the keyset cursor — the tiebreak that makes (time, id) a total order at a page seam.';

-- ── keyset indexes ────────────────────────────────────────────────────────
-- `lease.leases (status_id, created_at)`, `lease.breakdowns (status_id,
-- deadline)`, `lease.settlements (status_id, deadline)` and the visit
-- status/started_at index already exist (V31/V33). The clocks below read
-- through a join to `notraius.validity`, or filter a column no index covers
-- yet, so each gets the index its page is ordered by. Every one is
-- `IF NOT EXISTS` so a replay over a database that already grew the index is
-- a no-op rather than a failure.

-- The vigil's notice clocks: unanswered / answered notices past their window,
-- and the reconversion read. `response IS NULL` is the majority case, so the
-- plain btree is used; the validity join then reads by primary key.
CREATE INDEX IF NOT EXISTS idx_lease_notices_response_validity
    ON lease.notices USING btree (response, validity_id);
CREATE INDEX IF NOT EXISTS idx_lease_notices_type_response
    ON lease.notices USING btree (type_id, response);

-- The end-of-life and page-boundary clock itself. Without it the planner
-- walks the notices' validity join instead of the window's range.
CREATE INDEX IF NOT EXISTS idx_validity_to_date
    ON notraius.validity USING btree (to_date_time);

-- The visit expiry page is ordered by created_at under a status; the outcome
-- clocks page under a status by the window end (a join) or by the claim
-- instant (already indexed as idx_visits_status_started_at).
CREATE INDEX IF NOT EXISTS idx_visits_status_created_at
    ON visit.visits USING btree (status_id, created_at);
CREATE INDEX IF NOT EXISTS idx_visits_status_validity
    ON visit.visits USING btree (status_id, validity_id);

-- The lease end-of-life page is ordered by its validity's end under a status.
CREATE INDEX IF NOT EXISTS idx_leases_status_validity
    ON lease.leases USING btree (status_id, validity_id);

-- ── the seeds ─────────────────────────────────────────────────────────────
-- One row per job, so an operator can see the ledger before the first pass
-- runs. The cursors start at the beginning (epoch, nil uuid); the pager
-- tolerates a missing row just as happily, this only makes the table readable
-- from the first minute.
INSERT INTO notraius.sweep_cursors (job, cursor_key, cursor_id)
VALUES ('visit-expiry-request', 'epoch', '00000000-0000-0000-0000-000000000000'),
       ('visit-expiry-confirmation', 'epoch', '00000000-0000-0000-0000-000000000000'),
       ('visit-outcome-claims', 'epoch', '00000000-0000-0000-0000-000000000000'),
       ('visit-outcome-silent', 'epoch', '00000000-0000-0000-0000-000000000000'),
       ('visit-outcome-hour', 'epoch', '00000000-0000-0000-0000-000000000000'),
       ('lease-expiry', 'epoch', '00000000-0000-0000-0000-000000000000'),
       ('lease-vigil-notices', 'epoch', '00000000-0000-0000-0000-000000000000'),
       ('lease-vigil-answered-notices', 'epoch', '00000000-0000-0000-0000-000000000000'),
       ('lease-vigil-bills', 'epoch', '00000000-0000-0000-0000-000000000000'),
       ('lease-vigil-settlements', 'epoch', '00000000-0000-0000-0000-000000000000'),
       ('lease-vigil-endings', 'epoch', '00000000-0000-0000-0000-000000000000'),
       ('lease-vigil-horizons', 'epoch', '00000000-0000-0000-0000-000000000000'),
       ('lease-vigil-reconversions', 'epoch', '00000000-0000-0000-0000-000000000000')
ON CONFLICT (job) DO NOTHING;
