package com.notraius.modules.core.common.schedule;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of a sweep's page: the identity the pass acts on, and the value of
 * the sweep's own clock column on that row.
 * <p>
 * A sweep never loads entities to find its worklist. Ten million visits are
 * ten million hydrated objects; ten million <em>pairs</em> are two columns the
 * database can stream. The handler then loads the one row it acts on, inside
 * that row's own transaction, and the page's memory is gone before the next
 * page arrives.
 * <p>
 * The clock is the column the worklist is ordered by — {@code created_at} for
 * an expiry, {@code deadline} for a bill, the validity's end for a notice —
 * and together with {@link #getId()} it forms the keyset the cursor stores.
 * Implemented by Spring Data interface projections on the repository query
 * (aliases {@code id} and {@code sortKey}) and by {@link SweepRow} in tests.
 */
public interface SweepKey {

    /** The row the pass acts on. */
    UUID getId();

    /**
     * The clock value that orders the worklist. Never null: every sweep query
     * filters its clock column {@code IS NOT NULL} before paging.
     */
    Instant getSortKey();
}
