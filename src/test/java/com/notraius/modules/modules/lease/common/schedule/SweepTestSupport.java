package com.notraius.modules.modules.lease.common.schedule;

import com.notraius.config.notraius.properties.NotraiusProperties;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.mock;
import com.notraius.modules.core.core.common.schedule.sweep.SweepLedger;
import com.notraius.modules.core.core.common.schedule.sweep.SweepPager;

/**
 * A pager a unit test can drive without a database: a real {@link SweepPager}
 * over an in-memory {@link SweepLedger} and a mocked transaction manager (whose
 * transactions run inline — {@code getTransaction} returns null and the commit
 * is a no-op, so the handler is invoked exactly as the pager intends).
 * <p>
 * Hand-written rather than a Mockito mock on purpose: a mock returns {@code null}
 * for {@link SweepLedger#cursor}, and a pager that receives a null cursor cannot
 * page at all.
 */
public final class SweepTestSupport {

    private SweepTestSupport() {
    }

    /** A pager over an in-memory ledger, with the given (or default) properties. */
    public static SweepPager pager(NotraiusProperties properties) {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        return new SweepPager(transactionManager, new InMemoryLedger(),
                properties != null ? properties : new NotraiusProperties());
    }

    /** The ledger's cursors, in memory, one per job name. */
    public static final class InMemoryLedger implements SweepLedger {

        private final Map<String, Cursor> cursors = new HashMap<>();
        private final Map<String, Long> passes = new HashMap<>();

        @Override
        public Cursor cursor(String job) {
            return cursors.getOrDefault(job, Cursor.START);
        }

        @Override
        public void advance(String job, Cursor cursor, int rows) {
            cursors.put(job, cursor);
        }

        @Override
        public void completePass(String job) {
            cursors.put(job, Cursor.START);
            passes.merge(job, 1L, Long::sum);
        }

        /** Where the job stands — the test's view of the persisted cursor. */
        public Cursor at(String job) {
            return cursor(job);
        }

        /** How many times the job's worklist was exhausted. */
        public long passesOf(String job) {
            return passes.getOrDefault(job, 0L);
        }
    }
}
