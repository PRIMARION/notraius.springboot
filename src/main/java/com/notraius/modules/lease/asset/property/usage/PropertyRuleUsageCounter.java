package com.notraius.modules.lease.asset.property.usage;

import com.notraius.database.repositories.asset.asset.PropertyRuleSelectionRepository;
import com.notraius.modules.core.core.common.constants.NotraiusActivities;
import com.notraius.modules.core.core.common.constants.NotraiusStatuses;
import org.primarion.module.app.access.consent.consent.service.subscription.subscription.ActivityUsageCounter;
import org.primarion.module.app.access.consent.consent.service.subscription.subscription.UsageQuery;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Usage for the PROPERTY_RULES activity — the owner's rule selections.
 *
 * <p>A selection applies only while its validity status is ACTIVE and its
 * window is open. A stored ACTIVE selection past its end reads EXPIRED at
 * read time and no longer holds a live slot.</p>
 */
@Component
public class PropertyRuleUsageCounter implements ActivityUsageCounter {

    private final PropertyRuleSelectionRepository selectionRepository;

    public PropertyRuleUsageCounter(PropertyRuleSelectionRepository selectionRepository) {
        this.selectionRepository = selectionRepository;
    }

    @Override
    public String activityCode() {
        return NotraiusActivities.PROPERTY_RULES;
    }

    @Override
    public long countNew(UsageQuery query) {
        return selectionRepository.countNewByPropertyOwners(
                query.owners(), query.windowStart(), query.windowEnd());
    }

    @Override
    public long countActive(UsageQuery query) {
        return selectionRepository.countActiveByPropertyOwners(
                query.owners(), NotraiusStatuses.RULE_SELECTION_ACTIVE, Instant.now());
    }
}
