package com.notraius.modules.lease.asset.pricing.usage;

import com.notraius.database.repositories.asset.asset.PropertyPolicyValueRepository;
import com.notraius.modules.core.core.common.constants.NotraiusActivities;
import com.notraius.modules.core.core.common.constants.NotraiusStatuses;
import org.primarion.module.app.access.consent.consent.service.subscription.subscription.ActivityUsageCounter;
import org.primarion.module.app.access.consent.consent.service.subscription.subscription.UsageQuery;
import org.springframework.stereotype.Component;

/**
 * Usage for the PROPERTY_POLICIES activity — an owner's numbers inside the
 * admin's standards.
 *
 * <p>A value is active only while ACTIVE. PAUSED (its pricing stopped or the
 * standard was disabled) and FLAGGED (moderation) are not live values.</p>
 */
@Component
public class PropertyPolicyUsageCounter implements ActivityUsageCounter {

    private final PropertyPolicyValueRepository valueRepository;

    public PropertyPolicyUsageCounter(PropertyPolicyValueRepository valueRepository) {
        this.valueRepository = valueRepository;
    }

    @Override
    public String activityCode() {
        return NotraiusActivities.PROPERTY_POLICIES;
    }

    @Override
    public long countNew(UsageQuery query) {
        return valueRepository.countNewByPropertyOwners(
                query.owners(), query.windowStart(), query.windowEnd());
    }

    @Override
    public long countActive(UsageQuery query) {
        return valueRepository.countActiveByPropertyOwners(
                query.owners(), NotraiusStatuses.POLICY_VALUE_ACTIVE);
    }
}
