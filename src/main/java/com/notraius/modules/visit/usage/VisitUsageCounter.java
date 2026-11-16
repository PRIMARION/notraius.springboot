package com.notraius.modules.visit.usage;

import com.notraius.database.repositories.visit.VisitRepository;
import com.notraius.modules.core.common.constants.NotraiusActivities;
import com.notraius.modules.core.common.constants.NotraiusStatuses;
import org.primarion.module.access.consent.service.subscription.ActivityUsageCounter;
import org.primarion.module.access.consent.service.subscription.UsageQuery;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Usage for the VISITS activity.
 *
 * <p>A visit is live from the moment it is requested through every planning
 * and performance word: UNPAID (funding unknown, still stands), REQUESTED,
 * ASSIGNED, PLANNING, CONFIRMED and STARTED. COMPLETED, EXPIRED and CANCELLED
 * are terminal.</p>
 */
@Component
public class VisitUsageCounter implements ActivityUsageCounter {

    private static final List<String> ACTIVE_VISIT_STATUSES = List.of(
            NotraiusStatuses.VISIT_UNPAID,
            NotraiusStatuses.VISIT_REQUESTED,
            NotraiusStatuses.VISIT_ASSIGNED,
            NotraiusStatuses.VISIT_PLANNING,
            NotraiusStatuses.VISIT_CONFIRMED,
            NotraiusStatuses.VISIT_STARTED);

    private final VisitRepository visitRepository;

    public VisitUsageCounter(VisitRepository visitRepository) {
        this.visitRepository = visitRepository;
    }

    @Override
    public String activityCode() {
        return NotraiusActivities.VISITS;
    }

    @Override
    public long countNew(UsageQuery query) {
        return visitRepository.countByVisitorIdInAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                query.owners(), query.windowStart(), query.windowEnd());
    }

    @Override
    public long countActive(UsageQuery query) {
        return visitRepository.countActiveByVisitors(query.owners(), ACTIVE_VISIT_STATUSES);
    }
}
