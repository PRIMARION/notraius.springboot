package com.notraius.modules.core.common.schedule;

import com.notraius.config.properties.NotraiusProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The pager's own law: a budgeted pass over a worklist larger than one page must
 * resume where it stopped, a pass that reaches the end must start over, one
 * failing row must not stop the page, and every row must own its transaction.
 * <p>
 * These are the properties that make a ten-million-row sweep survivable — the
 * clock jobs are only as correct as this class.
 */
class SweepPagerTest {

    private static final String JOB = "test-sweep";

    @Test
    @DisplayName("a pass that hits the budget stops at the last settled row and the next pass resumes after it")
    void resumesAfterTheLastSettledRow() {
        NotraiusProperties properties = properties(2, 3);
        SweepTestSupport.InMemoryLedger ledger = new SweepTestSupport.InMemoryLedger();
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        SweepPager pager = new SweepPager(transactionManager, ledger, properties);

        List<SweepRow> worklist = rows(5);
        List<UUID> settled = new ArrayList<>();

        int first = pager.run(JOB, source(worklist), id -> {
            settled.add(id);
            return 1;
        });

        assertThat(first).isEqualTo(3);
        assertThat(settled).containsExactly(worklist.get(0).id(), worklist.get(1).id(), worklist.get(2).id());
        assertThat(ledger.at(JOB).key()).isEqualTo(worklist.get(2).sortKey());
        assertThat(ledger.at(JOB).id()).isEqualTo(worklist.get(2).id());
        assertThat(ledger.passesOf(JOB)).isZero();

        int second = pager.run(JOB, source(worklist), id -> {
            settled.add(id);
            return 1;
        });

        assertThat(second).isEqualTo(2);
        assertThat(settled).hasSize(5).doesNotHaveDuplicates();
        assertThat(ledger.passesOf(JOB)).isEqualTo(1);
        assertThat(ledger.at(JOB)).isEqualTo(SweepLedger.Cursor.START);
    }

    @Test
    @DisplayName("a short page is the end of the worklist: the cursor resets so the next pass retries failures")
    void shortPageResetsTheCursor() {
        NotraiusProperties properties = properties(10, 100);
        SweepTestSupport.InMemoryLedger ledger = new SweepTestSupport.InMemoryLedger();
        SweepPager pager = new SweepPager(mock(PlatformTransactionManager.class), ledger, properties);

        List<SweepRow> worklist = rows(3);
        pager.run(JOB, source(worklist), id -> 1);

        assertThat(ledger.at(JOB)).isEqualTo(SweepLedger.Cursor.START);
        assertThat(ledger.passesOf(JOB)).isEqualTo(1);
    }

    @Test
    @DisplayName("one row that cannot be settled is skipped, and the rest of the page still runs")
    void oneBadRowDoesNotStopThePage() {
        NotraiusProperties properties = properties(10, 100);
        SweepPager pager = new SweepPager(mock(PlatformTransactionManager.class),
                new SweepTestSupport.InMemoryLedger(), properties);

        List<SweepRow> worklist = rows(3);
        UUID broken = worklist.get(1).id();

        int settled = pager.run(JOB, source(worklist), id -> {
            if (id.equals(broken)) {
                throw new IllegalStateException("this row cannot be answered");
            }
            return 1;
        });

        assertThat(settled).isEqualTo(2);
    }

    @Test
    @DisplayName("every row owns a transaction — never the pass")
    void everyRowOwnsItsTransaction() {
        NotraiusProperties properties = properties(10, 100);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(null);
        SweepPager pager = new SweepPager(transactionManager,
                new SweepTestSupport.InMemoryLedger(), properties);

        pager.run(JOB, source(rows(4)), id -> 1);

        verify(transactionManager, times(4)).getTransaction(any(TransactionDefinition.class));
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private static NotraiusProperties properties(int pageSize, int budget) {
        NotraiusProperties properties = new NotraiusProperties();
        properties.setSweepPageSize(pageSize);
        properties.setSweepRowBudget(budget);
        return properties;
    }

    private static List<SweepRow> rows(int count) {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        List<SweepRow> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new SweepRow(UUID.randomUUID(), base.plusSeconds(i)));
        }
        return rows;
    }

    /** The worklist as the database would answer it: strictly after the cursor. */
    private static SweepPager.Source source(List<SweepRow> worklist) {
        List<SweepRow> ordered = worklist.stream()
                .sorted(Comparator.comparing(SweepRow::sortKey).thenComparing(SweepRow::id))
                .toList();
        return (afterKey, afterId, limit) -> ordered.stream()
                .filter(row -> row.sortKey().isAfter(afterKey)
                        || (row.sortKey().equals(afterKey) && row.id().compareTo(afterId) > 0))
                .limit(limit)
                .map(row -> (SweepKey) row)
                .toList();
    }
}
