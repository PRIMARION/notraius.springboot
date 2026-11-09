package com.notraius.modules.core.common.schedule;

import com.notraius.database.entities.core.SweepCursor;
import com.notraius.database.repositories.core.SweepCursorRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ledger on disk: one {@code notraius.sweep_cursors} row per job (V36).
 * <p>
 * Every write is its own transaction ({@code REQUIRES_NEW}) on purpose: the
 * pager calls this between the row transactions it owns, and a rollback of one
 * swept row must never roll back the record of the rows before it. It also
 * means the cursor is committed <em>after</em> the rows it covers — a crash
 * between the two replays a page, never skips one.
 */
@Component
public class JpaSweepLedger implements SweepLedger {

    private final SweepCursorRepository cursors;

    public JpaSweepLedger(SweepCursorRepository cursors) {
        this.cursors = cursors;
    }

    @Override
    @Transactional(readOnly = true)
    public Cursor cursor(String job) {
        return cursors.findByJob(job)
                .map(row -> new Cursor(row.getCursorKey(), row.getCursorId()))
                .orElse(Cursor.START);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void advance(String job, Cursor cursor, int rows) {
        SweepCursor row = row(job);
        row.setCursorKey(cursor.key());
        row.setCursorId(cursor.id());
        row.setRowsTouched(row.getRowsTouched() + Math.max(0, rows));
        cursors.save(row);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completePass(String job) {
        SweepCursor row = row(job);
        row.setCursorKey(Cursor.START.key());
        row.setCursorId(Cursor.START.id());
        row.setPasses(row.getPasses() + 1);
        cursors.save(row);
    }

    /** The job's row, created on first use so a new job needs no migration. */
    private SweepCursor row(String job) {
        return cursors.findByJob(job).orElseGet(() -> {
            SweepCursor fresh = new SweepCursor();
            fresh.setJob(job);
            fresh.setCursorKey(Cursor.START.key());
            fresh.setCursorId(Cursor.START.id());
            return fresh;
        });
    }
}
