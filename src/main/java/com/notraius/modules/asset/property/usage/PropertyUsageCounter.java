package com.notraius.modules.asset.property.usage;

import com.notraius.database.repositories.asset.PropertyRepository;
import com.notraius.modules.core.common.constants.NotraiusActivities;
import com.notraius.modules.core.common.constants.NotraiusStatuses;
import org.primarion.module.access.consent.service.subscription.ActivityUsageCounter;
import org.primarion.module.access.consent.service.subscription.UsageQuery;
import org.springframework.stereotype.Component;

/**
 * Usage for the PROPERTY activity.
 *
 * <p>A property is active when it is PUBLISHED (on the market) or LEASED (a
 * live tenancy occupies it). DRAFT is not yet a listing, PAUSED is the owner's
 * own unpublish, SUSPENDED is moderation, DISABLED is the deliberate dark
 * state, and DELETED is terminal. This is intentionally not "the one ACTIVE
 * status": the domain's live word differs by activity.</p>
 */
@Component
public class PropertyUsageCounter implements ActivityUsageCounter {

    private final PropertyRepository propertyRepository;

    public PropertyUsageCounter(PropertyRepository propertyRepository) {
        this.propertyRepository = propertyRepository;
    }

    @Override
    public String activityCode() {
        return NotraiusActivities.PROPERTY;
    }

    @Override
    public long countNew(UsageQuery query) {
        return propertyRepository.countByOwnerIdInAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                query.owners(), query.windowStart(), query.windowEnd());
    }

    @Override
    public long countActive(UsageQuery query) {
        return propertyRepository.countActiveByOwners(query.owners(), java.util.List.of(
                NotraiusStatuses.PROPERTY_PUBLISHED,
                NotraiusStatuses.PROPERTY_LEASED));
    }
}
