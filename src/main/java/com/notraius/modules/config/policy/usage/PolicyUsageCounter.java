package com.notraius.modules.config.policy.usage;

import com.notraius.database.repositories.config.PolicyRepository;
import com.notraius.modules.core.common.constants.NotraiusActivities;
import com.notraius.modules.core.common.constants.NotraiusStatuses;
import org.primarion.module.access.consent.service.subscription.ActivityUsageCounter;
import org.primarion.module.access.consent.service.subscription.UsageQuery;
import org.springframework.stereotype.Component;

/**
 * Usage for the POLICY activity — the admin's policy standards.
 *
 * <p>A policy is active while ENABLED; DISABLED is not live. Policies are a
 * shared operator catalogue with no per-user owner column, so the metered
 * scope is the catalogue itself.</p>
 */
@Component
public class PolicyUsageCounter implements ActivityUsageCounter {

    private final PolicyRepository policyRepository;

    public PolicyUsageCounter(PolicyRepository policyRepository) {
        this.policyRepository = policyRepository;
    }

    @Override
    public String activityCode() {
        return NotraiusActivities.POLICY;
    }

    @Override
    public long countNew(UsageQuery query) {
        return policyRepository.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                query.windowStart(), query.windowEnd());
    }

    @Override
    public long countActive(UsageQuery query) {
        return policyRepository.countByStatus_Code(NotraiusStatuses.POLICY_ENABLED);
    }
}
