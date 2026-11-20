package com.notraius.common.services.policy;

import com.notraius.database.entities.asset.config.Policy;
import com.notraius.database.repositories.asset.config.PolicyRepository;
import com.notraius.modules.core.core.common.constants.NotraiusStatuses;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The standards that govern one world.
 * <p>
 * The chain of PLAN.md 4.3 lives here in one place: a property resolves to a
 * state, and that state resolves to a set of standards — the state's own rows
 * plus the stateless defaults, with a state-specific row <em>shadowing</em> the
 * stateless row of the same code. "Same code, different world" is therefore a
 * first-class idea: Benin's ADVANCE and Nigeria's ADVANCE share the code and
 * share nothing else, and a state that has no ADVANCE of its own still gets an
 * answer — the global default — instead of a hole.
 * <p>
 * Only ENABLED standards resolve. A paused standard stops governing new values
 * while the values already set against it stay as history, so resolution is a
 * read-side filter rather than a rewrite of anything stored.
 */
@Service
public class PolicyResolver {

    private final PolicyRepository policyRepository;

    public PolicyResolver(PolicyRepository policyRepository) {
        this.policyRepository = policyRepository;
    }

    /**
     * Every standard that governs a state, one per code. Empty when the property
     * has no state: an unanchored property is stopped, never defaulted.
     */
    public List<Policy> applicable(UUID stateId) {
        if (stateId == null) {
            // An unanchored property is stopped, not defaulted: the stateless
            // rows are the fallback of a KNOWN state, not of an unknown one.
            return List.of();
        }
        return pickByCode(policyRepository.findCandidates(stateId, NotraiusStatuses.POLICY_ENABLED));
    }

    /**
     * Whether this concrete policy row still governs the state — the check that
     * stops a value being recorded against another world's standard, or against
     * a row that a state-specific one has since shadowed.
     */
    public Optional<Policy> applicable(UUID policyId, UUID stateId) {
        return applicable(stateId).stream()
                .filter(policy -> policy.getId().equals(policyId))
                .findFirst();
    }

    /** One winner per code; a state-specific row beats the stateless row. */
    private List<Policy> pickByCode(List<Policy> candidates) {
        Map<String, Policy> byCode = new LinkedHashMap<>();
        for (Policy candidate : candidates) {
            Policy existing = byCode.get(candidate.getCode());
            if (existing == null
                    || (existing.getStateId() == null && candidate.getStateId() != null)) {
                byCode.put(candidate.getCode(), candidate);
            }
        }
        return List.copyOf(byCode.values());
    }
}
