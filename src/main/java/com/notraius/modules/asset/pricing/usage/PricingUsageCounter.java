package com.notraius.modules.asset.pricing.usage;

import com.notraius.database.repositories.asset.PricingRepository;
import com.notraius.modules.core.common.constants.NotraiusActivities;
import com.notraius.modules.core.common.constants.NotraiusStatuses;
import org.primarion.module.access.consent.service.subscription.ActivityUsageCounter;
import org.primarion.module.access.consent.service.subscription.UsageQuery;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Usage for the PRICING activity.
 *
 * <p>Unlike PROPERTY, a pricing's live word really is the stored LIVE status —
 * but only while its validity window still answers now. An expired window is
 * reported as EXPIRED at read time, so a stale LIVE row outside its dates is
 * not an active pricing.</p>
 */
@Component
public class PricingUsageCounter implements ActivityUsageCounter {

    private final PricingRepository pricingRepository;

    public PricingUsageCounter(PricingRepository pricingRepository) {
        this.pricingRepository = pricingRepository;
    }

    @Override
    public String activityCode() {
        return NotraiusActivities.PRICING;
    }

    @Override
    public long countNew(UsageQuery query) {
        return pricingRepository.countNewByPropertyOwners(
                query.owners(), query.windowStart(), query.windowEnd());
    }

    @Override
    public long countActive(UsageQuery query) {
        return pricingRepository.countActiveByPropertyOwners(
                query.owners(), NotraiusStatuses.PRICING_LIVE, Instant.now());
    }
}
