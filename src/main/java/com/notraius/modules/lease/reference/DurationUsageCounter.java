package com.notraius.modules.lease.reference;

import com.notraius.database.repositories.lease.reference.duration.DurationRepository;
import com.notraius.modules.core.core.common.constants.NotraiusActivities;
import com.notraius.modules.core.core.common.constants.NotraiusStatuses;
import org.primarion.module.app.access.consent.consent.service.subscription.subscription.ActivityUsageCounter;
import org.primarion.module.app.access.consent.consent.service.subscription.subscription.UsageQuery;
import org.springframework.stereotype.Component;

/**
 * Usage for the DURATION activity — the reference duration catalogue.
 *
 * <p>A duration is active while ACTIVE; RETIRED is terminal. Durations are
 * shared catalogue rows, so the metered scope is the catalogue itself.</p>
 */
@Component
public class DurationUsageCounter implements ActivityUsageCounter {

    private final DurationRepository durationRepository;

    public DurationUsageCounter(DurationRepository durationRepository) {
        this.durationRepository = durationRepository;
    }

    @Override
    public String activityCode() {
        return NotraiusActivities.DURATION;
    }

    @Override
    public long countNew(UsageQuery query) {
        return durationRepository.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                query.windowStart(), query.windowEnd());
    }

    @Override
    public long countActive(UsageQuery query) {
        return durationRepository.countByStatus_Code(NotraiusStatuses.DURATION_ACTIVE);
    }
}
