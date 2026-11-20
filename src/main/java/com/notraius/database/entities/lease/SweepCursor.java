package com.notraius.database.entities.lease;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.primarion.database.entities.BaseEntity;

import java.time.Instant;
import java.util.UUID;

/**
 * The scheduled sweeps' ledger row (V36): what the last pass of one clock job
 * got through, and how much work it has done in total.
 * <p>
 * Infrastructure, not domain: nothing in Parts 2–6 reads this table, no user
 * sees it, and no lifecycle governs it. It exists because a budgeted pass over
 * a ten-million-row worklist has to be resumable, and because an operator
 * asked to prove that the money clock ran should be able to read the answer
 * instead of grepping a log.
 * <p>
 * One row per job, keyed by the job's stable name ({@code lease-expiry},
 * {@code lease-vigil-bills}, …). The cursor is the {@code (cursor_key,
 * cursor_id)} pair: the clock value and id of the last row the pass settled —
 * together a total order, so two rows sharing an instant cannot be skipped or
 * settled twice at a page seam.
 */
@Entity
@Table(name = "sweep_cursors", schema = "notraius",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_sweep_cursors_job", columnNames = "job"))
@Getter
@Setter
public class SweepCursor extends BaseEntity {

    /** The job's stable name — the same string the pager is called with. */
    @Column(name = "job", nullable = false, length = 64)
    private String job;

    /** The clock half of the keyset cursor. */
    @Column(name = "cursor_key", nullable = false)
    private Instant cursorKey;

    /** The id half of the keyset cursor. */
    @Column(name = "cursor_id", nullable = false)
    private UUID cursorId;

    /** Rows this job has settled, cumulatively — the operator's proof of work. */
    @Column(name = "rows_touched", nullable = false)
    private long rowsTouched;

    /** Completed passes, cumulatively. */
    @Column(name = "passes", nullable = false)
    private long passes;
}
