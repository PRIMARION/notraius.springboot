package com.notraius.modules.core.common.schedule;

import java.time.Instant;
import java.util.UUID;

/**
 * A {@link SweepKey} an ordinary class can build — the pager's own tests, and
 * any caller that needs to hand a page to {@link SweepPager} without a
 * database behind it.
 */
public record SweepRow(UUID id, Instant sortKey) implements SweepKey {

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public Instant getSortKey() {
        return sortKey;
    }
}
