package com.notraius.modules.lease.lease.agreement;

import com.notraius.database.repositories.lease.lease.lease.LeaseRepository;
import com.notraius.modules.core.core.common.constants.NotraiusActivities;
import com.notraius.modules.core.core.common.constants.NotraiusStatuses;
import org.primarion.module.app.access.consent.consent.service.subscription.subscription.ActivityUsageCounter;
import org.primarion.module.app.access.consent.consent.service.subscription.subscription.UsageQuery;
import org.springframework.stereotype.Component;

/**
 * Usage for the LEASES activity.
 *
 * <p>A lease is live while it is PENDING (funded intent), CONFIRMED (owner
 * committed, conditions due), ACTIVE (tenancy running) or DISPUTED (frozen by
 * a ticket). COMPLETED, CANCELLED and EXPIRED are terminal. The metered owner
 * is the tenant who started it.</p>
 */
@Component
public class LeaseUsageCounter implements ActivityUsageCounter {

    private final LeaseRepository leaseRepository;

    public LeaseUsageCounter(LeaseRepository leaseRepository) {
        this.leaseRepository = leaseRepository;
    }

    @Override
    public String activityCode() {
        return NotraiusActivities.LEASES;
    }

    @Override
    public long countNew(UsageQuery query) {
        return leaseRepository.countByUserIdInAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                query.owners(), query.windowStart(), query.windowEnd());
    }

    @Override
    public long countActive(UsageQuery query) {
        return leaseRepository.countActiveByTenants(
                query.owners(), NotraiusStatuses.LEASE_LIVE_STATUSES);
    }
}
