package com.notraius.modules.reference.amenity.usage;

import com.notraius.database.repositories.reference.AmenityRepository;
import com.notraius.modules.core.common.constants.NotraiusActivities;
import com.notraius.modules.core.common.constants.NotraiusStatuses;
import org.primarion.module.access.consent.service.subscription.ActivityUsageCounter;
import org.primarion.module.access.consent.service.subscription.UsageQuery;
import org.springframework.stereotype.Component;

/**
 * Usage for the AMENITY activity.
 *
 * <p>A proposed amenity is active only once moderation makes it ACTIVE.
 * PENDING is a proposal, REJECTED/CANCELLED are refused, DISABLED is switched
 * off, and RETIRED is terminal. Admin-authored amenities have no proposer and
 * therefore belong to no subscriber's metered rows.</p>
 */
@Component
public class AmenityUsageCounter implements ActivityUsageCounter {

    private final AmenityRepository amenityRepository;

    public AmenityUsageCounter(AmenityRepository amenityRepository) {
        this.amenityRepository = amenityRepository;
    }

    @Override
    public String activityCode() {
        return NotraiusActivities.AMENITY;
    }

    @Override
    public long countNew(UsageQuery query) {
        return amenityRepository.countByProposerInAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                query.owners(), query.windowStart(), query.windowEnd());
    }

    @Override
    public long countActive(UsageQuery query) {
        return amenityRepository.countActiveByProposers(
                query.owners(), NotraiusStatuses.AMENITY_ACTIVE);
    }
}
