package com.notraius.modules.config.policies.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.notraius.common.audit.AuditTrail;
import com.notraius.common.NotraiusErrors;
import com.notraius.common.services.PolicyValueSeeder;
import com.notraius.common.services.QueryFilters;
import com.notraius.common.services.RevisionService;
import com.notraius.common.services.StatusResolver;
import com.notraius.database.entities.config.Policy;
import com.notraius.database.entities.config.PolicyUnit;
import com.notraius.database.entities.reference.Entities;
import com.notraius.database.entities.reference.Type;
import com.notraius.database.repositories.config.PolicyRepository;
import com.notraius.database.repositories.reference.EntitiesRepository;
import com.notraius.database.repositories.reference.TypeRepository;
import com.notraius.modules.config.policies.dto.request.PolicyCreateRequest;
import com.notraius.modules.config.policies.dto.request.PolicyUpdateRequest;
import com.notraius.modules.config.policies.dto.response.PolicyResponse;
import com.notraius.modules.asset.terms.service.TermsService;
import com.notraius.modules.config.policies.mapper.PolicyMapper;
import com.notraius.modules.core.common.constants.NotraiusActivities;
import com.notraius.modules.core.common.constants.NotraiusStatuses;
import lombok.extern.slf4j.Slf4j;
import com.notraius.common.dto.responses.RevisionSummaryResponse;
import org.primarion.module.errors.BadRequestException;
import org.primarion.module.errors.ConflictException;
import org.primarion.module.errors.NotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The config module's policies sub-module (PLAN.md 3.2) — the admin standards.
 * <p>
 * <b>The strictest governance in the module.</b> The admin defines the policy,
 * its bounds, its mandatory flag, its unit and its status; every other tier
 * reads it and nothing else. The read side is therefore the whole user-facing
 * surface here, which is why no endpoint in this sub-module is open below the
 * personal tier.
 * <p>
 * <b>What the row means.</b> (entity, type) says what the standard governs —
 * ADVANCE is a policy of type INSTALLMENT, so the lease machinery derives split
 * behaviour from type and unit alone. {@code stateId} says which world it
 * governs (a Benin standard may differ from a Nigerian one; null is the
 * stateless fallback). {@code unit} says how the value is read and is fixed at
 * birth.
 * <p>
 * <b>Pause is reversible, and nothing is destroyed.</b> Disabling stops the
 * standard being offered and enforced for new configurations while values
 * already set against it stay as a property's history; re-enabling restores
 * enforcement and re-evaluates those values, flagging — never clamping — the
 * ones the admin has since tightened out of range.
 * <p>
 * <b>Versioning.</b> Every accepted edit bumps {@code version} and writes its
 * before/after entry in the same transaction, so "what did the standard say, and
 * what document backed it, at version four" stays answerable.
 * <p>
 * <b>The three cascades are live</b> (they run in the same transaction):
 * <ul>
 *   <li>enable → re-evaluate stored values against the bounds and flag the
 *       out-of-range ones on their property (never rewrite them);</li>
 *   <li>mandatory flip → backfill every pricing in this standard's world at the
 *       minimum, refusing when the policy has no minimum;</li>
 *   <li>disable → mark the values already set against it as "paused" on their
 *       property, keeping the numbers.</li>
 * </ul>
 * A standard's world is resolved through {@link com.notraius.common.services.PolicyResolver}:
 * a state's own row governs that state, the stateless row governs the states
 * that have none of their own — so a cascade never reaches across markets.
 */
@Service
@Slf4j
public class PolicyService {

    private final PolicyRepository policyRepository;
    private final EntitiesRepository entitiesRepository;
    private final TypeRepository typeRepository;
    private final StatusResolver statusResolver;
    private final TermsService termsService;
    private final PolicyValueSeeder policyValueSeeder;
    private final PolicyMapper policyMapper;
    private final AuditTrail auditTrail;
    private final QueryFilters queryFilters;
    private final RevisionService revisionService;

    public PolicyService(PolicyRepository policyRepository,
                         EntitiesRepository entitiesRepository,
                         TypeRepository typeRepository,
                         StatusResolver statusResolver,
                         TermsService termsService,
                         PolicyValueSeeder policyValueSeeder,
                         PolicyMapper policyMapper,
                         AuditTrail auditTrail,
                         QueryFilters queryFilters,
                         RevisionService revisionService) {
        this.policyRepository = policyRepository;
        this.entitiesRepository = entitiesRepository;
        this.typeRepository = typeRepository;
        this.statusResolver = statusResolver;
        this.termsService = termsService;
        this.policyValueSeeder = policyValueSeeder;
        this.policyMapper = policyMapper;
        this.auditTrail = auditTrail;
        this.queryFilters = queryFilters;
        this.revisionService = revisionService;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Create and read
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Define a policy. It is born ENABLED — there is no user-visible draft
     * state — and at version one, whose audit entry is the base of its history.
     */
    @Transactional
    public PolicyResponse createPolicy(PolicyCreateRequest request, UUID userId) {
        String entityCode = normalise(request.getEntityCode());
        Entities entity = entitiesRepository.findByCode(entityCode)
                .orElseThrow(() -> new BadRequestException(NotraiusErrors.POLICY_ENTITY_NOT_FOUND,
                        "Entity not found: " + entityCode));

        Type type = typeRepository.findById(request.getTypeId())
                .orElseThrow(() -> new BadRequestException(NotraiusErrors.POLICY_TYPE_NOT_FOUND,
                        "Type not found: " + request.getTypeId()));
        if (!type.getEntity().getId().equals(entity.getId())) {
            throw new BadRequestException(NotraiusErrors.POLICY_TYPE_ENTITY_MISMATCH,
                    "Type " + type.getCode() + " belongs to entity " + type.getEntity().getCode()
                            + " but this policy is namespaced by " + entityCode);
        }

        PolicyUnit unit = PolicyUnit.from(request.getUnit());
        if (unit == null) {
            throw new BadRequestException(NotraiusErrors.POLICY_UNIT_INVALID,
                    "Unknown policy unit: " + request.getUnit());
        }
        requireValidBounds(request.getMin(), request.getMax(), unit);

        String code = normalise(request.getCode());
        // The identity is (entity, code, state): one stateless standard per code
        // and one standard per (code, state). Benin's ADVANCE and Nigeria's
        // ADVANCE are two legitimate rows (PLAN.md 4.3).
        UUID stateId = request.getStateId();
        boolean duplicate = stateId == null
                ? policyRepository.existsByEntityIdAndCodeAndStateIdIsNull(entity.getId(), code)
                : policyRepository.existsByEntityIdAndCodeAndStateId(entity.getId(), code, stateId);
        if (duplicate) {
            throw new ConflictException(NotraiusErrors.POLICY_CODE_DUPLICATE,
                    "A policy with code '" + code + "' already exists for entity " + entityCode
                            + (stateId == null ? " (stateless)" : " in state " + stateId));
        }

        // A standard is funded at a lease's birth, not at its own: the deposit
        // matrix names every commitment by a LEASE_DEPOSITS type carrying the
        // same code. Creating the standard before that type exists would make
        // every property in its world publishable and unrentable (the matrix
        // throws LEASE_DEPOSIT_TYPE_MISSING at the tenant's first attempt), so
        // the ordering is refused here, where an admin can still fix it by
        // creating the type first.
        if (typeRepository.findByEntityCodeAndCode(Entities.LEASE_DEPOSITS, code).isEmpty()) {
            throw new ConflictException(NotraiusErrors.POLICY_DEPOSIT_TYPE_MISSING,
                    "No LEASE_DEPOSITS type is declared for '" + code
                            + "'; create that type first, because every lease names its custody by it");
        }

        Policy policy = new Policy();
        policy.setEntity(entity);
        policy.setType(type);
        policy.setCode(code);
        policy.setTitle(request.getTitle().trim());
        policy.setDescription(trimToNull(request.getDescription()));
        policy.setMinimum(request.getMin());
        policy.setMaximum(request.getMax());
        policy.setMandatory(request.isMandatory());
        policy.setUnit(unit);
        policy.setVersion(1);
        policy.setStateId(request.getStateId());
        policy.setStatus(statusResolver.resolve(Entities.POLICIES, NotraiusStatuses.POLICY_ENABLED));
        policy.setCreatedBy(userId);

        policyRepository.save(policy);
        log.info("Policy created: id={} entity={} code={}", policy.getId(), entityCode, code);
        audit(NotraiusActivities.Policies.CREATE, policy.getId(), "CREATE", policyMapper.toResponse(policy), null);
        return policyMapper.toResponse(policy);
    }

    /**
     * The catalogue page. Filters compose: an entity (what namespace), a type
     * (what the standard governs), a state (which world — a Benin property is
     * shown Benin's standards), a status and free text. A selection UI asks for
     * {@code status=ENABLED}; a disabled policy is never offered as a choice.
     */
    @Transactional(readOnly = true)
    public Page<PolicyResponse> listPolicies(String entityCode, UUID typeId, UUID stateId,
                                             String statusCode, String search, Pageable pageable) {
        return policyRepository.search(queryFilters.entity(entityCode), typeId, stateId,
                        queryFilters.status(Entities.POLICIES, statusCode),
                        queryFilters.search(search), pageable)
                .map(policyMapper::toResponse);
    }

    /**
     * Read one policy. A value already set against a disabled policy stays
     * visible on the property marked as paused, so by-id reads never hide a
     * disabled row — the status travels in the body.
     */
    @Transactional(readOnly = true)
    public PolicyResponse getPolicy(UUID policyId) {
        return getPolicy(policyId, null);
    }

    /**
     * The standard as one version stated it.
     * <p>
     * A standard is public configuration (its title, description and bounds are
     * the market's own rules), so its history is public for the same reason the
     * row is: a property's value cites the standard behind it, and "what did the
     * bound say when this value was set" is the question that citation invites.
     * The row itself keeps only its newest truth, so the answer comes from the
     * audit chain in this DTO's shape.
     */
    @Transactional(readOnly = true)
    public PolicyResponse getPolicy(UUID policyId, Integer version) {
        Policy policy = requirePolicy(policyId);
        if (version == null || version.equals(policy.getVersion())) {
            return policyMapper.toResponse(policy);
        }
        return revisionService.revisionOf(Entities.POLICIES, policyId, version, PolicyResponse.class);
    }

    /**
     * Which revisions of this standard the chain holds.
     * <p>
     * Public for the reason the standard's history is public: a property's value
     * cites the bound behind it, and a client reconciling its copy against the
     * catalogue wants the version list, not a guessing protocol. What it exposes
     * is only what the catalogue already states — which revisions exist and when
     * each became current — never who wrote them.
     */
    @Transactional(readOnly = true)
    public List<RevisionSummaryResponse> listRevisions(UUID policyId) {
        Policy policy = requirePolicy(policyId);
        return revisionService.revisionsOf(Entities.POLICIES, policyId, policy.getVersion());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Edit, mandatory, status
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Edit the text and the bounds. Tightening the bounds is legitimate and is
     * applied to future value-setting; stored values it excludes are
     * grandfathered with a review flag, never silently clamped.
     */
    @Transactional
    public PolicyResponse updatePolicy(UUID policyId, PolicyUpdateRequest request) {
        Policy policy = requirePolicy(policyId);

        // Snapshot BEFORE the first mutation — the chain's old side has to be
        // the old truth, or the history entry states the new bounds twice.
        PolicyResponse before = policyMapper.toResponse(policy);

        BigDecimal minimum = request.getMin() != null ? request.getMin() : policy.getMinimum();
        BigDecimal maximum = request.getMax() != null ? request.getMax() : policy.getMaximum();

        boolean changed = false;
        if (request.getMin() != null || request.getMax() != null) {
            requireValidBounds(minimum, maximum, policy.getUnit());
            policy.setMinimum(minimum);
            policy.setMaximum(maximum);
            changed = true;
        }
        if (request.getTitle() != null) {
            policy.setTitle(request.getTitle().trim());
            changed = true;
        }
        if (request.getDescription() != null) {
            policy.setDescription(trimToNull(request.getDescription()));
            changed = true;
        }
        if (!changed) {
            return before;
        }

        policy.setVersion(policy.getVersion() + 1);
        policyRepository.save(policy);

        PolicyResponse after = policyMapper.toResponse(policy);
        audit(NotraiusActivities.Policies.UPDATE, policyId, "UPDATE", after, before);
        return after;
    }

    /**
     * Flip the mandatory flag — the compliance switch.
     * <p>
     * Mandatory means every pricing this policy governs must carry a value before
     * its property publishes, so the flip backfills those pricings at the
     * minimum; properties that are already live are grandfathered rather than
     * unpublished, and every future pricing is seeded at birth.
     * <p>
     * The backfill scopes to this standard's own world: a state's standard seeds
     * that state's pricings, a stateless default seeds the pricings of every
     * state that has no standard of its own under this code. Another market's
     * standards never touch a property, and a property with no state gets
     * nothing — it is stopped, not defaulted.
     */
    @Transactional
    public PolicyResponse setMandatory(UUID policyId, boolean mandatory) {
        Policy policy = requirePolicy(policyId);
        if (policy.isMandatory() == mandatory) {
            return policyMapper.toResponse(policy);
        }

        PolicyResponse before = policyMapper.toResponse(policy);
        policy.setMandatory(mandatory);
        policy.setVersion(policy.getVersion() + 1);
        policyRepository.save(policy);

        int seeded = mandatory ? policyValueSeeder.backfillMandatory(policy, null) : 0;

        PolicyResponse after = policyMapper.toResponse(policy);
        audit(NotraiusActivities.Policies.MANDATORY, policyId, "MANDATORY", after, before);
        log.info("Policy {} mandatory={} backfilledValues={}", policyId, mandatory, seeded);
        return after;
    }

    /**
     * Pause the standard. Its values are paused with it — kept exactly as the
     * owner left them, un-editable while the standard is off.
     */
    @Transactional
    public PolicyResponse disablePolicy(UUID policyId) {
        Policy policy = requirePolicy(policyId);
        requireStatus(policy, NotraiusStatuses.POLICY_ENABLED, "disable");

        PolicyResponse before = policyMapper.toResponse(policy);
        policy.setStatus(statusResolver.resolve(Entities.POLICIES, NotraiusStatuses.POLICY_DISABLED));
        // A pause is a truth the row states, and values are locked with it: bump
        // so the chain can answer "what did the standard say while it was off?".
        policy.setVersion(policy.getVersion() + 1);
        policyRepository.save(policy);

        int paused = termsService.pauseValuesOfPolicy(policyId);

        PolicyResponse after = policyMapper.toResponse(policy);
        audit(NotraiusActivities.Policies.DISABLE, policyId, "DISABLE", after, before);
        log.info("Policy {} disabled; {} values paused", policyId, paused);
        return after;
    }

    /**
     * Restore enforcement. Existing values are re-evaluated in that moment: one
     * that still fits passes silently, one the admin has since tightened out of
     * range is flagged "needs review" on its property — never auto-corrected,
     * because the plan does not rewrite a user's stored number behind their back.
     */
    @Transactional
    public PolicyResponse enablePolicy(UUID policyId) {
        Policy policy = requirePolicy(policyId);
        requireStatus(policy, NotraiusStatuses.POLICY_DISABLED, "enable");

        PolicyResponse before = policyMapper.toResponse(policy);
        policy.setStatus(statusResolver.resolve(Entities.POLICIES, NotraiusStatuses.POLICY_ENABLED));
        policy.setVersion(policy.getVersion() + 1);
        policyRepository.save(policy);

        int flagged = termsService.resumeValuesOfPolicy(policy);

        PolicyResponse after = policyMapper.toResponse(policy);
        audit(NotraiusActivities.Policies.ENABLE, policyId, "ENABLE", after, before);
        log.info("Policy {} enabled; {} values flagged out of bounds", policyId, flagged);
        return after;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private Policy requirePolicy(UUID policyId) {
        return policyRepository.findById(policyId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.POLICY_NOT_FOUND,
                        "Policy not found: " + policyId));
    }

    /** Advisory-strict bounds: a value must fit inside them when it is set. */
    /**
     * Bounds are what the seeder writes into a fresh pricing and what the lease
     * gate must be able to turn into money, so the same law is applied at the
     * standard's birth as at the owner's number: nothing may permit zero (a
     * commitment of zero funds nothing) and a counting standard may only bound
     * whole units (half a month is not a number of installments).
     */
    private void requireValidBounds(BigDecimal minimum, BigDecimal maximum, PolicyUnit unit) {
        if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
            throw new BadRequestException(NotraiusErrors.POLICY_BOUNDS_INVALID,
                    "Minimum " + minimum + " exceeds maximum " + maximum);
        }
        for (BigDecimal bound : new BigDecimal[]{minimum, maximum}) {
            if (bound == null) {
                continue;
            }
            if (bound.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BadRequestException(NotraiusErrors.POLICY_BOUNDS_INVALID,
                        "Bound " + bound + " funds nothing; bounds must be greater than zero");
            }
            if (PolicyUnit.UNITS == unit && bound.stripTrailingZeros().scale() > 0) {
                throw new BadRequestException(NotraiusErrors.POLICY_BOUNDS_INVALID,
                        "Bound " + bound + " counts units; a counting standard's bounds must be whole numbers");
            }
        }
    }

    private void requireStatus(Policy policy, String expected, String operation) {
        String actual = policy.getStatus() != null ? policy.getStatus().getCode() : null;
        if (!expected.equals(actual)) {
            throw new ConflictException(NotraiusErrors.POLICY_INVALID_STATE,
                    "Policy is " + actual + "; " + operation + " requires " + expected);
        }
    }

    /**
     * A standard's history is a read model, not a shadow: the entry is written
     * through {@link RequiredAuditWriter#writeRequired}, so a failure rolls the
     * version bump back with it.
     */
    private void audit(String activityCode, UUID referenceId, String operation, Object after, Object before) {
        auditTrail.recordRequired(Entities.POLICIES, activityCode, referenceId, operation, before, after);
    }

    private String normalise(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase(java.util.Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
