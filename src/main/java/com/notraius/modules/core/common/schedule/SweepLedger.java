package com.notraius.modules.core.common.schedule;

import java.time.Instant;
import java.util.UUID;

/**
 * Where a sweep stopped, and the proof that it ran.
 * <p>
 * The clock jobs of Parts 5 and 6 are budgeted: one tick settles at most
 * {@code app.notraius.sweep-row-budget} rows, so a large worklist is worked
 * through over several ticks instead of being read into memory whole. That is
 * only safe if the next tick knows where the last one stopped — otherwise it
 * would re-read the first page forever and never reach the tenth million.
 * <p>
 * Two rules make the cursor safe to be behind:
 * <ul>
 *   <li><b>It never skips a row.</b> It is advanced past a row only after that
 *       row's own transaction returned; a row that failed keeps its status, so
 *       the worklist predicate still matches it and the next full pass — the
 *       one that starts at {@link Cursor#START} — retries it.</li>
 *   <li><b>It is reset when the worklist is exhausted.</b> A pass that read a
 *       short page knows it has seen everything the predicate matches <em>from
 *       its cursor</em>; resetting means the following pass looks at the whole
 *       worklist again, so nothing that was behind the cursor waiting to become
 *       eligible (a lease that only just aged past its window) is stranded.</li>
 * </ul>
 */
public interface SweepLedger {

    /** The beginning of every worklist: epoch, nil uuid. */
    record Cursor(Instant key, UUID id) {

        public static final UUID NIL = new UUID(0L, 0L);
        public static final Cursor START = new Cursor(Instant.EPOCH, NIL);

        public boolean isStart() {
            return Instant.EPOCH.equals(key) && NIL.equals(id);
        }
    }

    /** Where this job stopped; {@link Cursor#START} for a job that never ran. */
    Cursor cursor(String job);

    /**
     * Move the cursor to a settled row and count the rows this page touched.
     * Called once per page, not once per row: a pass that dies mid-page loses
     * at most the page's cursor move, never a settled row's transaction.
     */
    void advance(String job, Cursor cursor, int rows);

    /** The worklist was exhausted from the cursor: start the next pass at the beginning. */
    void completePass(String job);
}
