package com.notraius.modules.lease.visit.usage;

import com.notraius.database.repositories.lease.visit.VisitPricingRepository;
import com.notraius.modules.core.core.common.constants.NotraiusActivities;
import com.notraius.modules.core.core.common.constants.NotraiusStatuses;
import org.primarion.module.app.access.consent.consent.service.subscription.subscription.ActivityUsageCounter;
import org.primarion.module.app.access.consent.consent.service.subscription.subscription.UsageQuery;
import org.springframework.stereotype.Component;

/**
 * Usage for the VISIT_PRICINGS activity.
 *
 * <p>A visit price is active while ACTIVE; PAUSED is the owner's own off
 * switch and stops counting.</p>
 */
@Component
public class VisitPricingUsageCounter implements ActivityUsageCounter {

    private final VisitPricingRepository visitPricingRepository;

    public VisitPricingUsageCounter(VisitPricingRepository visitPricingRepository) {
        this.visitPricingRepository = visitPricingRepository;
    }

    @Override
    public String activityCode() {
        return NotraiusActivities.VISIT_PRICINGS;
    }

    @Override
    public long countNew(UsageQuery query) {
        return visitPricingRepository.countNewByPropertyOwners(
                query.owners(), query.windowStart(), query.windowEnd());
    }

    @Override
    public long countActive(UsageQuery query) {
        return visitPricingRepository.countActiveByPropertyOwners(
                query.owners(), NotraiusStatuses.VISIT_PRICING_ACTIVE);
    }
}
