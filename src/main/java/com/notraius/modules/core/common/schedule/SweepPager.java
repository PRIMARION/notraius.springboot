package com.notraius.modules.core.common.schedule;

import com.notraius.config.properties.NotraiusProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.ToIntFunction;

/**
 * Runs one sweep pass over a bounded page at a time.
 * <p>
 * <b>Why.</b> Every clock in Parts 5 and 6 used to answer "what is due?" with
 * one query that returned every matching row, then walked the list. That is
 * fine while the table is a seed and wrong at the sizes this domain is built
 * for: ten million due rows is ten million hydrated entities held at once, one
 * giant transaction, and a pass that can never finish. At that size the useful
 * question is not "can I read it all?" but "can I make steady progress?".
 * <p>
 * <b>What one pass does.</b>
 * <ol>
 *   <li>Asks the worklist for one page — at most {@code sweep-page-size} keys —
 *       strictly after the cursor the previous pass stored, ordered by
 *       {@code (clock, id)}.</li>
 *   <li>Settles each key in <em>its own</em> transaction, so a row whose truth
 *       cannot be answered rolls back alone and the rows around it are not
 *       dragged down with it. That is the same law the lease sweep already
 *       states: one row, one transaction.</li>
 *   <li>Moves the cursor past the page, so a pass that hits the budget stops
 *       and the next one continues from the seam instead of re-reading the
 *       start.</li>
 *   <li>When a page comes back short, the worklist is exhausted from the
 *       cursor: the cursor is reset, so the next pass looks at the whole
 *       worklist again — including rows this pass failed on, and rows that
 *       were not eligible yet when it walked past them.</li>
 * </ol>
 * <p>
 * <b>Collision.</b> Two guards, at two different scales:
 * <ul>
 *   <li>The keyset cursor is exclusive: a new pass starts <em>after</em> the
 *       last row the running pass settled, never on it, so the element at the
 *       seam is worked exactly once.</li>
 *   <li>Cross-instance overlap is prevented before a pass even starts, by the
 *       Redis mutex each scheduled job holds around this call
 *       ({@link NotraiusSweepJobs}); the cursor is what makes the run after
 *       that mutex is released resume rather than restart.</li>
 * </ul>
 * <p>
 * <b>Failure is not fatal.</b> A row that throws is logged and the pass moves
 * past it — one unanswerable funding truth must never stop the clock for every
 * other row. The row's status is unchanged, so it stays in the worklist and
 * the next full pass retries it.
 */
@Component
public class SweepPager {

    private static final Logger log = LoggerFactory.getLogger(SweepPager.class);

    private final TransactionTemplate transactions;
    private final SweepLedger ledger;
    private final NotraiusProperties properties;

    public SweepPager(PlatformTransactionManager transactionManager,
                      SweepLedger ledger,
                      NotraiusProperties properties) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.ledger = ledger;
        this.properties = properties;
    }

    /**
     * One page of a worklist: the rows strictly after {@code (afterKey,
     * afterId)}, ordered by that same pair, capped at {@code limit}. A fresh
     * cursor ({@link SweepLedger.Cursor#START}) asks for the head of the list.
     */
    @FunctionalInterface
    public interface Source {

        List<SweepKey> page(Instant afterKey, UUID afterId, int limit);
    }

    /**
     * Run one budgeted pass.
     *
     * @param job    the ledger's stable name for this worklist
     * @param source the worklist, in {@code (clock, id)} order
     * @param settle acts on one row in its own transaction; returns how many
     *               units it actually moved (0 for a row that turned out not to
     *               need the act), so the pass can report real work
     * @return units moved by the rows this pass settled
     */
    public int run(String job, Source source, ToIntFunction<UUID> settle) {
        int pageSize = Math.max(1, properties.getSweepPageSize());
        int budget = Math.max(pageSize, properties.getSweepRowBudget());
        SweepLedger.Cursor cursor = ledger.cursor(job);
        int touched = 0;
        int acted = 0;

        while (touched < budget) {
            int limit = Math.min(pageSize, budget - touched);
            List<SweepKey> page = source.page(cursor.key(), cursor.id(), limit);
            if (page == null || page.isEmpty()) {
                // Nothing left after the cursor: the worklist is done. Reset so
                // the next pass starts from the head again, where rows that were
                // skipped (failed, or not yet eligible) still live.
                ledger.completePass(job);
                break;
            }

            for (SweepKey row : page) {
                touched++;
                try {
                    Integer moved = transactions.execute(status -> settle.applyAsInt(row.getId()));
                    acted += moved != null ? moved : 0;
                } catch (RuntimeException e) {
                    // One bad row must never stop the clock for the rest: the
                    // row keeps its status, so the next full pass retries it.
                    log.warn("Sweep '{}' could not settle row {} (pass continues): {}",
                            job, row.getId(), e.getMessage());
                }
                cursor = new SweepLedger.Cursor(row.getSortKey(), row.getId());
            }
            ledger.advance(job, cursor, page.size());

            if (page.size() < limit) {
                ledger.completePass(job);
                break;
            }
        }

        if (acted > 0) {
            log.info("Sweep '{}' settled {} of {} row(s) this pass", job, acted, touched);
        } else {
            log.debug("Sweep '{}' settled nothing of {} row(s) this pass", job, touched);
        }
        return acted;
    }
}
