package com.notraius.modules.core.config;

import com.notraius.database.repositories.asset.config.RuleRepository;
import com.notraius.modules.core.core.common.constants.NotraiusActivities;
import com.notraius.modules.core.core.common.constants.NotraiusStatuses;
import org.primarion.module.app.access.consent.consent.service.subscription.subscription.ActivityUsageCounter;
import org.primarion.module.app.access.consent.consent.service.subscription.subscription.UsageQuery;
import org.springframework.stereotype.Component;

/**
 * Usage for the RULE activity — a user's own house rules.
 *
 * <p>A rule is active while ACTIVE. DISABLED (the owner switched it off) and
 * RETIRED are not live rules.</p>
 */
@Component
public class RuleUsageCounter implements ActivityUsageCounter {

    private final RuleRepository ruleRepository;

    public RuleUsageCounter(RuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    @Override
    public String activityCode() {
        return NotraiusActivities.RULE;
    }

    @Override
    public long countNew(UsageQuery query) {
        return ruleRepository.countByCreatorIdInAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                query.owners(), query.windowStart(), query.windowEnd());
    }

    @Override
    public long countActive(UsageQuery query) {
        return ruleRepository.countActiveByCreators(
                query.owners(), NotraiusStatuses.RULE_ACTIVE);
    }
}
