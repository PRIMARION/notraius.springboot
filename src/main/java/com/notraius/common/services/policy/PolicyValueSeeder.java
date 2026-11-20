package com.notraius.common.services.policy;

import com.notraius.common.NotraiusErrors;
import com.notraius.config.notraius.properties.NotraiusProperties;
import com.notraius.database.entities.asset.asset.Pricing;
import com.notraius.database.entities.asset.asset.PropertyPolicyValue;
import com.notraius.database.entities.asset.config.Policy;
import com.notraius.database.entities.asset.config.PolicyUnit;
import com.notraius.database.entities.lease.reference.amenity.Entities;
import com.notraius.database.repositories.asset.asset.PricingRepository;
import com.notraius.database.repositories.asset.asset.PropertyPolicyValueRepository;
import com.notraius.modules.core.core.common.constants.NotraiusStatuses;
import com.notraius.modules.core.core.common.schedule.sweep.SweepLedger;
import lombok.extern.slf4j.Slf4j;
import org.primarion.module.communication.errors.client.BadRequestException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import com.notraius.common.services.logs.StatusResolver;
import com.notraius.common.services.policy.PolicyResolver;

/**
 * The only thing that ever creates a policy value on its own: a <em>mandatory</em>
 * standard, seeded at its minimum.
 * <p>
 * Two moments ask for it. A pricing is born → every mandatory standard governing
 * the property's world gets its value at once, so a fresh pricing is publishable
 * rather than blocked by homework nobody was told about. And an admin flips
 * "mandatory" on → every pricing already in that standard's world that lacks the
 * value gets it. In both cases the number seeded is the <b>minimum</b>: the
 * strictest value the standard permits, never a guess between min and max.
 * <p>
 * A mandatory standard with no minimum cannot be satisfied at all; rather than
 * invent a number it refuses, loudly, and the pricing or the flip fails as a
 * whole. That is the operator's rule that a mandatory policy must have a floor.
 */
@Service
@Slf4j
public class PolicyValueSeeder {

    private final PolicyResolver policyResolver;
    private final PricingRepository pricingRepository;
    private final PropertyPolicyValueRepository valueRepository;
    private final StatusResolver statusResolver;
    private final NotraiusProperties properties;

    public PolicyValueSeeder(PolicyResolver policyResolver,
                             PricingRepository pricingRepository,
                             PropertyPolicyValueRepository valueRepository,
                             StatusResolver statusResolver,
                             NotraiusProperties properties) {
        this.policyResolver = policyResolver;
        this.pricingRepository = pricingRepository;
        this.valueRepository = valueRepository;
        this.statusResolver = statusResolver;
        this.properties = properties;
    }

    /** Seed every missing mandatory standard for a pricing that was just born. */
    public int seedMandatory(Pricing pricing, UUID actor) {
        UUID stateId = pricing.getProperty() != null ? pricing.getProperty().getStateId() : null;
        int seeded = 0;
        for (Policy policy : policyResolver.applicable(stateId)) {
            if (!policy.isMandatory()) {
                continue;
            }
            seed(pricing, policy, actor);
            seeded++;
        }
        return seeded;
    }

    /**
     * Backfill a standard that just became mandatory. Scoped exactly as the
     * standard governs: the state's own pricings, or — for a stateless default —
     * the pricings of every world that has no standard of its own under the code.
     * <p>
     * <b>Paged, and flushed per page.</b> A standard flipped mandatory can govern
     * every pricing in a state, so the set is walked in id-ordered pages of
     * {@code sweep-page-size}: seed the page, flush it, and only then read the
     * next — a page's values are already written when the next page is hydrated,
     * so the persistence context never holds the whole backfill and the
     * {@code NOT EXISTS} predicate sees its own effect.
     * <p>
     * The transaction is the operator's act: if the standard has no minimum to
     * seed from, {@link #seed} refuses and the whole backfill rolls back, exactly
     * as a single bad number always has.
     */
    @Transactional
    public int backfillMandatory(Policy policy, UUID actor) {
        int pageSize = Math.max(1, properties.getSweepPageSize());
        int seeded = 0;
        UUID afterId = SweepLedger.Cursor.NIL;

        while (true) {
            List<Pricing> page = policy.getStateId() != null
                    ? pricingRepository.findMissingValueForPolicyPage(policy.getId(), policy.getStateId(),
                            afterId, PageRequest.of(0, pageSize))
                    : pricingRepository.findMissingValueForStatelessPolicyPage(policy.getCode(), policy.getId(),
                            NotraiusStatuses.POLICY_ENABLED, afterId, PageRequest.of(0, pageSize));
            if (page.isEmpty()) {
                break;
            }
            for (Pricing pricing : page) {
                seed(pricing, policy, actor);
                seeded++;
            }
            afterId = page.get(page.size() - 1).getId();
            valueRepository.flush();
            if (page.size() < pageSize) {
                break;
            }
        }

        if (seeded > 0) {
            log.info("Backfilled policy {} at its minimum into {} pricings", policy.getId(), seeded);
        }
        return seeded;
    }

    private void seed(Pricing pricing, Policy policy, UUID actor) {
        // The same law the owner's own numbers are held to (NT-0096): the seeded
        // value becomes a commitment, so a floor the matrix cannot fund is refused
        // here - the pricing's birth or the admin's flip rolls back - rather than
        // written into a property that would then refuse every tenant.
        if (policy.getMinimum() == null
                || policy.getMinimum().compareTo(BigDecimal.ZERO) <= 0
                || (PolicyUnit.UNITS == policy.getUnit()
                    && policy.getMinimum().stripTrailingZeros().scale() > 0)) {
            throw new BadRequestException(NotraiusErrors.PRICING_POLICY_MINIMUM_MISSING,
                    "Policy '" + policy.getCode() + "' is mandatory but its minimum "
                            + policy.getMinimum() + " is not a number a lease can be funded from");
        }
        PropertyPolicyValue value = new PropertyPolicyValue();
        value.setPricing(pricing);
        value.setPolicy(policy);
        value.setValue(policy.getMinimum());
        value.setStatus(statusResolver.resolve(Entities.PROPERTY_POLICIES, NotraiusStatuses.POLICY_VALUE_ACTIVE));
        value.setVersion(1);
        value.setCreatedBy(actor);
        valueRepository.save(value);
    }
}
