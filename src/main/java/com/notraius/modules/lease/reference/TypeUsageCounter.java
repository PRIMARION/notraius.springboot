package com.notraius.modules.lease.reference;

import com.notraius.database.repositories.lease.reference.duration.TypeRepository;
import com.notraius.modules.core.core.common.constants.NotraiusActivities;
import com.notraius.modules.core.core.common.constants.NotraiusStatuses;
import org.primarion.module.app.access.consent.consent.service.subscription.subscription.ActivityUsageCounter;
import org.primarion.module.app.access.consent.consent.service.subscription.subscription.UsageQuery;
import org.springframework.stereotype.Component;

/**
 * Usage for the TYPE activity — the hierarchical reference catalogue.
 *
 * <p>A type is active while ACTIVE; RETIRED is terminal. Types are shared
 * catalogue rows, not per-user resources, so the metered scope is the
 * catalogue itself.</p>
 */
@Component
public class TypeUsageCounter implements ActivityUsageCounter {

    private final TypeRepository typeRepository;

    public TypeUsageCounter(TypeRepository typeRepository) {
        this.typeRepository = typeRepository;
    }

    @Override
    public String activityCode() {
        return NotraiusActivities.TYPE;
    }

    @Override
    public long countNew(UsageQuery query) {
        return typeRepository.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                query.windowStart(), query.windowEnd());
    }

    @Override
    public long countActive(UsageQuery query) {
        return typeRepository.countByStatus_Code(NotraiusStatuses.TYPE_ACTIVE);
    }
}
