package com.notraius.modules.asset.terms.service;

import com.notraius.common.audit.AuditTrail;
import com.notraius.common.NotraiusErrors;
import com.notraius.common.dto.responses.RevisionSummaryResponse;
import com.notraius.common.services.PolicyResolver;
import com.notraius.common.services.RevisionService;
import com.notraius.common.services.StatusResolver;
import com.notraius.config.properties.NotraiusProperties;
import com.notraius.database.entities.asset.Pricing;
import com.notraius.database.entities.asset.Property;
import com.notraius.database.entities.asset.PropertyPolicyValue;
import com.notraius.database.entities.asset.PropertyRuleSelection;
import com.notraius.database.entities.config.Policy;
import com.notraius.database.entities.config.PolicyUnit;
import com.notraius.database.entities.config.Rule;
import com.notraius.database.entities.reference.Entities;
import com.notraius.database.entities.reference.Validity;
import com.notraius.database.repositories.asset.PricingRepository;
import com.notraius.database.repositories.asset.PropertyPolicyValueRepository;
import com.notraius.database.repositories.asset.PropertyRepository;
import com.notraius.database.repositories.asset.PropertyRuleSelectionRepository;
import com.notraius.database.repositories.config.PolicyRepository;
import com.notraius.database.repositories.config.RuleRepository;
import com.notraius.database.repositories.reference.ValidityRepository;
import com.notraius.modules.asset.terms.dto.request.PolicyValueSetRequest;
import com.notraius.modules.asset.terms.dto.request.RuleSelectRequest;
import com.notraius.modules.asset.terms.dto.response.PolicyValueResponse;
import com.notraius.modules.asset.terms.dto.response.RuleSelectionResponse;
import com.notraius.modules.asset.terms.mapper.TermsMapper;
import com.notraius.modules.core.common.Windows;
import com.notraius.modules.core.common.constants.NotraiusActivities;
import com.notraius.modules.core.common.constants.NotraiusStatuses;
import com.notraius.modules.core.common.schedule.SweepLedger;
import lombok.extern.slf4j.Slf4j;
import org.primarion.module.access.consent.service.OwnershipGuard;
import org.primarion.module.errors.BadRequestException;
import org.primarion.module.errors.ConflictException;
import org.primarion.module.errors.ForbiddenException;
import org.primarion.module.errors.NotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The asset module's terms sub-module (PLAN.md 4.3) — what the property commits
 * to beyond its price.
 * <p>
 * <b>Two kinds of term, one law.</b> A <em>policy value</em> is the owner's
 * number inside an admin standard (the admin owns ADVANCE, its unit and its
 * bounds; the owner decides 3 months). A <em>rule selection</em> is the owner's
 * promise to respect a rule they wrote or a public one, held for a window of
 * their choosing. Neither is configuration: configuration is global and
 * admin-only, these are per-property and owner-only.
 * <p>
 * <b>The standards are resolved, not chosen.</b> A value may only be set against
 * a standard that actually governs the property's world — its own state's row,
 * or the stateless default when the state has none. Another state's standard is
 * refused: the numbers are not comparable and the units may not even agree.
 * <p>
 * <b>Bounds are advisory-strict at the door, never rewritten at the back.</b>
 * A number set out of bounds is refused outright; a number the admin later
 * squeezes out of bounds is flagged for review by the policy cascade, because
 * the system does not silently correct a user's own commitment.
 * <p>
 * <b>Forgetting is ownership, not suppression.</b> A rule can be selected for
 * overlapping-free windows and forgotten completely; the row and its window go
 * together, because a selection without a window means nothing.
 */
@Service
@Slf4j
public class TermsService extends OwnershipGuard {

    private final PropertyRepository propertyRepository;
    private final PricingRepository pricingRepository;
    private final PropertyPolicyValueRepository valueRepository;
    private final PropertyRuleSelectionRepository selectionRepository;
    private final RuleRepository ruleRepository;
    private final PolicyRepository policyRepository;
    private final ValidityRepository validityRepository;
    private final PolicyResolver policyResolver;
    private final StatusResolver statusResolver;
    private final TermsMapper termsMapper;
    private final RevisionService revisionService;
    private final AuditTrail auditTrail;
    private final NotraiusProperties properties;

    public TermsService(PropertyRepository propertyRepository,
                        PricingRepository pricingRepository,
                        PropertyPolicyValueRepository valueRepository,
                        PropertyRuleSelectionRepository selectionRepository,
                        RuleRepository ruleRepository,
                        PolicyRepository policyRepository,
                        ValidityRepository validityRepository,
                        PolicyResolver policyResolver,
                        StatusResolver statusResolver,
                        TermsMapper termsMapper,
                        RevisionService revisionService,
                        AuditTrail auditTrail,
                        NotraiusProperties properties) {
        this.propertyRepository = propertyRepository;
        this.pricingRepository = pricingRepository;
        this.valueRepository = valueRepository;
        this.selectionRepository = selectionRepository;
        this.ruleRepository = ruleRepository;
        this.policyRepository = policyRepository;
        this.validityRepository = validityRepository;
        this.policyResolver = policyResolver;
        this.statusResolver = statusResolver;
        this.termsMapper = termsMapper;
        this.revisionService = revisionService;
        this.auditTrail = auditTrail;
        this.properties = properties;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Policy values — the owner's number inside the admin's standard
    // ═══════════════════════════════════════════════════════════════════════

    /** The values one pricing carries. Published properties show them to anyone. */
    @Transactional(readOnly = true)
    public List<PolicyValueResponse> listValues(UUID pricingId, UUID callerId) {
        Pricing pricing = requireReadablePricing(pricingId, callerId);
        return valueRepository.findByPricingId(pricing.getId()).stream()
                .map(termsMapper::toValueResponse)
                .toList();
    }

    /**
     * One value, or the value as revision {@code version} stated it.
     * <p>
     * The discovery gap the parent's door closed for rules and prices applied here
     * too: a value publishes its {@code version} in every response, so a client
     * holding version 3 had no way to ask for it and no way to learn that 4 was
     * all there is. Same semantics as the four top-level doors — absent or equal
     * to the row's version is the live read, lower is answered from the chain, a
     * version the chain does not hold is refused by name.
     */
    @Transactional(readOnly = true)
    public PolicyValueResponse getValue(UUID pricingId, UUID policyId, Integer version, UUID callerId) {
        Pricing pricing = requireReadablePricing(pricingId, callerId);
        PropertyPolicyValue value = requireValue(pricing.getId(), policyId);
        if (version == null || version == value.getVersion()) {
            return termsMapper.toValueResponse(value);
        }
        return revisionService.revisionOf(Entities.PROPERTY_POLICIES, value.getId(), version,
                PolicyValueResponse.class);
    }

    /** The revisions of one value the chain holds — the past, listed rather than guessed. */
    @Transactional(readOnly = true)
    public List<RevisionSummaryResponse> listValueRevisions(UUID pricingId, UUID policyId, UUID callerId) {
        Pricing pricing = requireReadablePricing(pricingId, callerId);
        PropertyPolicyValue value = requireValue(pricing.getId(), policyId);
        return revisionService.revisionsOf(Entities.PROPERTY_POLICIES, value.getId(), value.getVersion());
    }

    /**
     * Set (or re-set) the owner's number for one standard on one pricing.
     * <p>
     * The standard must govern this property's world, be enabled, and the number
     * must be non-negative and inside its bounds. An existing value is rewritten
     * in place with a version bump and a before/after entry — the owner's
     * history is the pricing's story, not a pile of rows.
     */
    @Transactional
    public PolicyValueResponse setValue(UUID pricingId, PolicyValueSetRequest request, UUID callerId) {
        Pricing pricing = requireOwnedPricing(pricingId, callerId);
        Property property = pricing.getProperty();
        UUID stateId = property.getStateId();
        if (stateId == null) {
            throw new ConflictException(NotraiusErrors.PROPERTY_STATE_UNRESOLVABLE,
                    "Property " + property.getId() + " has no resolved state, so no standard applies to it");
        }

        Policy policy = resolveStandard(request.getPolicyId(), stateId);
        BigDecimal value = request.getValue();
        requireValidValue(policy, value);

        PropertyPolicyValue existing = valueRepository
                .findByPricingIdAndPolicyId(pricingId, policy.getId())
                .orElse(null);

        if (existing == null) {
            PropertyPolicyValue created = new PropertyPolicyValue();
            created.setPricing(pricing);
            created.setPolicy(policy);
            created.setValue(value);
            created.setStatus(statusResolver.resolve(Entities.PROPERTY_POLICIES, NotraiusStatuses.POLICY_VALUE_ACTIVE));
            created.setVersion(1);
            created.setCreatedBy(callerId);
            valueRepository.save(created);

            PolicyValueResponse response = termsMapper.toValueResponse(created);
            audit(NotraiusActivities.Terms.POLICY_VALUE_SET, created.getId(), "SET", response, null);
            return response;
        }

        PolicyValueResponse before = termsMapper.toValueResponse(existing);
        existing.setValue(value);
        // Setting a number makes it current: a value paused by a disable, or
        // flagged by a squeeze, becomes ACTIVE again by the owner's own act.
        existing.setStatus(statusResolver.resolve(Entities.PROPERTY_POLICIES, NotraiusStatuses.POLICY_VALUE_ACTIVE));
        existing.setVersion(existing.getVersion() + 1);
        valueRepository.save(existing);

        PolicyValueResponse after = termsMapper.toValueResponse(existing);
        audit(NotraiusActivities.Terms.POLICY_VALUE_SET, existing.getId(), "SET", after, before);
        return after;
    }

    /**
     * Forget the owner's number for one standard on one pricing.
     * <p>
     * The other half of the plan's own escape (PLAN.md 4.3: "the owner removes or
     * edits the values first"): a count-kind value is exactly what refuses a
     * pricing's duration change, so the owner must be able to take the number
     * away rather than be locked into the cadence forever. A lease already funded
     * holds its own deposits; forgetting changes only the terms a <em>future</em>
     * lease derives.
     * <p>
     * The removal is not a way around a mandatory standard: publication and lease
     * birth both refuse, loudly, until the owner sets the number again.
     */
    @Transactional
    public void forgetValue(UUID pricingId, UUID policyId, UUID callerId) {
        Pricing pricing = requireOwnedPricing(pricingId, callerId);
        PropertyPolicyValue value = requireValue(pricing.getId(), policyId);

        PolicyValueResponse before = termsMapper.toValueResponse(value);
        UUID valueId = value.getId();
        valueRepository.delete(value);
        audit(NotraiusActivities.Terms.POLICY_VALUE_FORGET, valueId, "FORGET", null, before);
    }

    /**
     * The publication gate's terms half: every pricing of the property carries a
     * value for every mandatory standard that governs its world.
     * <p>
     * Seeding at birth normally guarantees this; the gate exists because a
     * standard can turn mandatory after the pricing was born (the backfill covers
     * that too) and because a state can be anchored while standards change
     * underneath. Publishing is the one moment the property tells the market "my
     * terms are complete", so the claim is verified rather than assumed.
     */
    @Transactional(readOnly = true)
    public void requireMandatoryValuesComplete(Property property) {
        List<Policy> mandatory = policyResolver.applicable(property.getStateId()).stream()
                .filter(Policy::isMandatory)
                .toList();
        if (mandatory.isEmpty()) {
            return;
        }
        List<Pricing> pricings = pricingRepository.findByPropertyId(property.getId());
        if (pricings.isEmpty()) {
            return;
        }
        List<PropertyPolicyValue> values = valueRepository
                .findByPricingIdIn(pricings.stream().map(Pricing::getId).toList());

        for (Pricing pricing : pricings) {
            for (Policy policy : mandatory) {
                PropertyPolicyValue current = values.stream()
                        .filter(value -> value.getPricing().getId().equals(pricing.getId())
                                && value.getPolicy().getId().equals(policy.getId()))
                        .findFirst()
                        .orElse(null);
                if (current == null) {
                    throw new ConflictException(NotraiusErrors.TERM_VALUE_REQUIRED,
                            "Pricing " + pricing.getId() + " has no value for the mandatory standard '"
                                    + policy.getCode() + "'");
                }
                // Present is not enough: a flagged number is one the bounds have
                // already refused, and a paused one belongs to a standard that no
                // longer governs. Publishing is the listing's claim that it can be
                // leased, so the claim is checked against the same law the lease
                // matrix applies - not merely against the row's existence.
                String valueStatus = statusCodeOf(current);
                if (!NotraiusStatuses.POLICY_VALUE_ACTIVE.equals(valueStatus)) {
                    throw new ConflictException(NotraiusErrors.TERM_VALUE_REQUIRED,
                            "Pricing " + pricing.getId() + " holds a " + valueStatus
                                    + " value for the mandatory standard '" + policy.getCode()
                                    + "'; it needs a current number before the property publishes");
                }
            }
        }
    }

    /**
     * Lock every value of a standard that was just paused. The numbers are kept
     * exactly as the owner left them — pausing the standard does not erase the
     * property's commitment, it stops the commitment being edited until the
     * standard returns.
     */
    @Transactional
    public int pauseValuesOfPolicy(UUID policyId) {
        int paused = 0;
        int pageSize = Math.max(1, properties.getSweepPageSize());
        UUID afterId = SweepLedger.Cursor.NIL;
        while (true) {
            List<PropertyPolicyValue> page = valueRepository.findByPolicyIdPage(policyId, afterId,
                    PageRequest.of(0, pageSize));
            if (page.isEmpty()) {
                break;
            }
            for (PropertyPolicyValue value : page) {
                if (NotraiusStatuses.POLICY_VALUE_PAUSED.equals(statusCodeOf(value))) {
                    continue;
                }
                PolicyValueResponse before = termsMapper.toValueResponse(value);
                value.setStatus(statusResolver.resolve(Entities.PROPERTY_POLICIES, NotraiusStatuses.POLICY_VALUE_PAUSED));
                // A pause is a truth the value states, and the response publishes
                // its version: leave the number alone and two different truths
                // share one revision, which the chain cannot replay.
                value.setVersion(value.getVersion() + 1);
                valueRepository.save(value);
                audit(NotraiusActivities.Terms.POLICY_VALUE_SET, value.getId(), "PAUSE",
                        termsMapper.toValueResponse(value), before);
                paused++;
            }
            afterId = page.get(page.size() - 1).getId();
            valueRepository.flush();
            if (page.size() < pageSize) {
                break;
            }
        }
        return paused;
    }

    /**
     * Re-evaluate every value of a standard that was just re-enabled: one that
     * still fits its bounds goes back ACTIVE, one the admin has since squeezed
     * out is FLAGGED — never clamped, never dropped.
     */
    @Transactional
    public int resumeValuesOfPolicy(Policy policy) {
        int flagged = 0;
        int pageSize = Math.max(1, properties.getSweepPageSize());
        UUID afterId = SweepLedger.Cursor.NIL;
        while (true) {
            List<PropertyPolicyValue> page = valueRepository.findByPolicyIdPage(policy.getId(), afterId,
                    PageRequest.of(0, pageSize));
            if (page.isEmpty()) {
                break;
            }
            for (PropertyPolicyValue value : page) {
                boolean inBounds = withinBounds(policy, value.getValue());
                String target = inBounds
                        ? NotraiusStatuses.POLICY_VALUE_ACTIVE
                        : NotraiusStatuses.POLICY_VALUE_FLAGGED;
                if (!inBounds) {
                    flagged++;
                }
                if (target.equals(statusCodeOf(value))) {
                    continue;
                }
                PolicyValueResponse before = termsMapper.toValueResponse(value);
                value.setStatus(statusResolver.resolve(Entities.PROPERTY_POLICIES, target));
                value.setVersion(value.getVersion() + 1);
                valueRepository.save(value);
                audit(NotraiusActivities.Terms.POLICY_VALUE_SET, value.getId(),
                        inBounds ? "RESUME" : "FLAG", termsMapper.toValueResponse(value), before);
            }
            afterId = page.get(page.size() - 1).getId();
            valueRepository.flush();
            if (page.size() < pageSize) {
                break;
            }
        }
        return flagged;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Rule selections — the owner's promises, held for a window
    // ═══════════════════════════════════════════════════════════════════════

    /** The rules the property holds. Visible with the property, not by visibility. */
    @Transactional(readOnly = true)
    public List<RuleSelectionResponse> listRuleSelections(UUID propertyId, UUID callerId) {
        Property property = requireReadableProperty(propertyId, callerId);
        Instant now = Instant.now();
        return selectionRepository.findByPropertyId(property.getId()).stream()
                .map(selection -> termsMapper.toSelectionResponse(selection,
                        effectiveSelectionStatus(selection, now)))
                .toList();
    }

    /**
     * One selection, or the selection as revision {@code version} stated it.
     * <p>
     * Authorization is the property's, exactly as for the list: a selection is a
     * promise the property makes, and a past one can name a rule the property has
     * since forgotten. The status is <b>re-derived at read time</b> from the
     * snapshot's window and stored act, so the history door and the live door
     * cannot disagree about the same version (the defect fixed for prices in
     * UPGRADE §12/V10).
     */
    @Transactional(readOnly = true)
    public RuleSelectionResponse getRuleSelection(UUID propertyId, UUID selectionId, Integer version,
                                                 UUID callerId) {
        Property property = requireReadableProperty(propertyId, callerId);
        PropertyRuleSelection selection = requireSelection(property.getId(), selectionId);
        if (version == null || version == selection.getVersion()) {
            return termsMapper.toSelectionResponse(selection, effectiveSelectionStatus(selection, Instant.now()));
        }
        RuleSelectionResponse snapshot = revisionService.revisionOf(Entities.PROPERTY_RULES, selectionId,
                version, RuleSelectionResponse.class);
        return statusAtNow(snapshot);
    }

    /** The revisions of one selection the chain holds. */
    @Transactional(readOnly = true)
    public List<RevisionSummaryResponse> listRuleSelectionRevisions(UUID propertyId, UUID selectionId,
                                                                   UUID callerId) {
        Property property = requireReadableProperty(propertyId, callerId);
        PropertyRuleSelection selection = requireSelection(property.getId(), selectionId);
        return revisionService.revisionsOf(Entities.PROPERTY_RULES, selectionId, selection.getVersion());
    }

    /**
     * Select a rule for this property with its own window.
     * <p>
     * Selectable means: my own rule, or a public one. Not someone else's private
     * rule, however that rule reached my screen. Only a live (ACTIVE) rule is
     * selectable, and two selections of the same rule may not own the same
     * instant — the same rule would then apply twice with no way to say which.
     */
    @Transactional
    public RuleSelectionResponse selectRule(UUID propertyId, RuleSelectRequest request, UUID callerId) {
        Property property = requireOwnedProperty(propertyId, callerId);
        Rule rule = ruleRepository.findById(request.getRuleId())
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.TERM_RULE_NOT_FOUND,
                        "Rule not found: " + request.getRuleId()));

        if (!isSelectable(rule, callerId)) {
            throw new ConflictException(NotraiusErrors.TERM_RULE_NOT_SELECTABLE,
                    "Rule " + rule.getId() + " is private to another owner");
        }
        if (!NotraiusStatuses.RULE_ACTIVE.equals(statusCodeOf(rule))) {
            throw new ConflictException(NotraiusErrors.TERM_RULE_NOT_LIVE,
                    "Rule " + rule.getId() + " is " + statusCodeOf(rule) + " and cannot be selected");
        }

        Instant now = Instant.now();
        Instant from = request.getFromDate() != null ? request.getFromDate() : now;
        Instant to = request.getToDate();
        requireValidWindow(from, to);

        boolean overlapping = selectionRepository.findByPropertyId(property.getId()).stream()
                .filter(selection -> selection.getRule().getId().equals(rule.getId()))
                .anyMatch(selection -> overlaps(selection.getValidity(), from, to));
        if (overlapping) {
            throw new ConflictException(NotraiusErrors.TERM_RULE_ALREADY_SELECTED,
                    "Rule " + rule.getId() + " already applies to this property in an overlapping window");
        }

        Validity validity = statusResolver.openValidity(Entities.PROPERTY_RULES,
                NotraiusStatuses.RULE_SELECTION_ACTIVE);
        validity.setFromDate(from);
        validity.setToDate(to);
        validityRepository.save(validity);

        PropertyRuleSelection selection = new PropertyRuleSelection();
        selection.setProperty(property);
        selection.setRule(rule);
        selection.setValidity(validity);
        selection.setVersion(1);
        selection.setCreatedBy(callerId);
        selectionRepository.save(selection);

        RuleSelectionResponse response = termsMapper.toSelectionResponse(selection,
                effectiveSelectionStatus(selection, now));
        audit(Entities.PROPERTY_RULES, NotraiusActivities.Terms.RULE_SELECT, selection.getId(),
                "SELECT", response, null);
        return response;
    }

    /** Forget a selection: the row and its window go together. */
    @Transactional
    public void forgetRule(UUID propertyId, UUID selectionId, UUID callerId) {
        Property property = requireOwnedProperty(propertyId, callerId);
        PropertyRuleSelection selection = requireSelection(property.getId(), selectionId);

        RuleSelectionResponse before = termsMapper.toSelectionResponse(selection,
                effectiveSelectionStatus(selection, Instant.now()));
        Validity validity = selection.getValidity();
        selectionRepository.delete(selection);
        // The selection holds the FK, so its row must leave first; the flush makes
        // the order the database sees explicit instead of leaving it to the
        // action queue's ordering.
        selectionRepository.flush();
        if (validity != null) {
            validityRepository.delete(validity);
        }
        audit(Entities.PROPERTY_RULES, NotraiusActivities.Terms.RULE_FORGET, selectionId, "FORGET", null, before);
    }

    /**
     * The deletion's reference release (PLAN.md 4.1): a property that leaves the
     * market cannot keep its rules locked, so its selections are forgotten with
     * it - each removal an entry of its own, exactly as if the owner had pressed
     * forget.
     * <p>
     * Called by {@code PropertyService.deleteProperty} after its ownership and
     * in-use gates have passed: the caller has already established the right to
     * act, and a deleted property can no longer answer for itself.
     * <p>
     * The rows are removed in one batch rather than one flush per selection: the
     * snapshots for the audit entries are taken first (while the rows still
     * exist), then every selection goes in a single flush, then their windows.
     * The FK lives on the selection, so that order is the database's requirement,
     * not a preference.
     */
    @Transactional
    public int releaseSelections(UUID propertyId) {
        List<PropertyRuleSelection> selections = selectionRepository.findByPropertyId(propertyId);
        if (selections.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        // The audit entry per selection needs the snapshot taken before the row
        // goes, so the snapshots are built first and the deletes are batched: the
        // selection rows (which hold the FK) leave in one flush, then their
        // windows. A per-selection flush would cost a round trip each and leave
        // the order to nothing but luck.
        Map<UUID, RuleSelectionResponse> snapshots = new LinkedHashMap<>();
        for (PropertyRuleSelection selection : selections) {
            snapshots.put(selection.getId(), termsMapper.toSelectionResponse(selection,
                    effectiveSelectionStatus(selection, now)));
        }
        selectionRepository.deleteAll(selections);
        selectionRepository.flush();
        for (PropertyRuleSelection selection : selections) {
            if (selection.getValidity() != null) {
                validityRepository.delete(selection.getValidity());
            }
        }
        snapshots.forEach((selectionId, before) ->
                audit(Entities.PROPERTY_RULES, NotraiusActivities.Terms.RULE_FORGET, selectionId,
                        "FORGET", null, before));
        return selections.size();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // OwnershipGuard — the property's owner
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * args[0]: the property, args[1]: the caller id. A property has exactly one
     * owner — the row's {@code owner_id} — and staff moderation never becomes
     * ownership of its terms.
     */
    @Override
    public boolean canAccess(Object... args) {
        if (args.length < 2) {
            return false;
        }
        if (!(args[0] instanceof Property property) || !(args[1] instanceof UUID callerId)) {
            return false;
        }
        return callerId != null && callerId.equals(property.getOwnerId());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    /** The value one pricing holds for one standard — the row's identity, not a filter. */
    private PropertyPolicyValue requireValue(UUID pricingId, UUID policyId) {
        return valueRepository.findByPricingIdAndPolicyId(pricingId, policyId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.TERM_VALUE_NOT_SET,
                        "Pricing " + pricingId + " carries no value for policy " + policyId));
    }

    /** The selection one property holds, or a refusal that does not confirm the id. */
    private PropertyRuleSelection requireSelection(UUID propertyId, UUID selectionId) {
        return selectionRepository.findByIdAndPropertyId(selectionId, propertyId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.TERM_RULE_SELECTION_NOT_FOUND,
                        "Selection " + selectionId + " does not belong to property " + propertyId));
    }

    /**
     * A snapshot read back at the present instant: the window decides the status,
     * exactly as the live door decides it. Snapshots written before the act was
     * republished ({@code storedStatusCode}) carry only the derived word, so the
     * act falls back to ACTIVE — the only act a selection is ever created with.
     */
    private RuleSelectionResponse statusAtNow(RuleSelectionResponse snapshot) {
        String act = snapshot.getStoredStatusCode() != null
                ? snapshot.getStoredStatusCode()
                : NotraiusStatuses.RULE_SELECTION_ACTIVE;
        snapshot.setStatusCode(Windows.statusIn(act, NotraiusStatuses.RULE_SELECTION_EXPIRED,
                snapshot.getFromDate(), snapshot.getToDate(), Instant.now()));
        return snapshot;
    }

    private Pricing requireOwnedPricing(UUID pricingId, UUID callerId) {
        Pricing pricing = requirePricing(pricingId);
        requireOwnership("Only the property's owner may change its terms", pricing.getProperty(), callerId);
        if (NotraiusStatuses.PROPERTY_SUSPENDED.equals(statusCodeOf(pricing.getProperty()))) {
            throw new ConflictException(NotraiusErrors.PROPERTY_INVALID_STATE,
                    "Property " + pricing.getProperty().getId() + " is suspended; its terms cannot change");
        }
        return pricing;
    }

    private Pricing requireReadablePricing(UUID pricingId, UUID callerId) {
        Pricing pricing = requirePricing(pricingId);
        requireReadableProperty(pricing.getProperty().getId(), callerId);
        return pricing;
    }

    private Pricing requirePricing(UUID pricingId) {
        return pricingRepository.findById(pricingId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.PRICING_NOT_FOUND,
                        "Pricing not found: " + pricingId));
    }

    /** A suspended property is frozen, terms included: moderation's stop is not editable around. */
    private Property requireOwnedProperty(UUID propertyId, UUID callerId) {
        Property property = requireProperty(propertyId);
        requireOwnership("Only the property's owner may change its terms", property, callerId);
        if (NotraiusStatuses.PROPERTY_SUSPENDED.equals(statusCodeOf(property))) {
            throw new ConflictException(NotraiusErrors.PROPERTY_INVALID_STATE,
                    "Property " + propertyId + " is suspended; its terms cannot change");
        }
        return property;
    }

    private Property requireReadableProperty(UUID propertyId, UUID callerId) {
        Property property = requireProperty(propertyId);
        if (NotraiusStatuses.PROPERTY_PUBLISHED.equals(statusCodeOf(property))) {
            return property;
        }
        // A draft answers as absent — 403 would confirm the id exists, which is
        // the anti-enumeration rule PropertyService.requireUnpublishedInvisible
        // sets for GET /properties/{id}. Same answer for its sub-resources.
        try {
            requireOwnership("Only the property's owner may read its terms", property, callerId);
        } catch (ForbiddenException e) {
            throw new NotFoundException(NotraiusErrors.PROPERTY_NOT_FOUND,
                    "Property not found: " + propertyId);
        }
        return property;
    }

    private Property requireProperty(UUID propertyId) {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.PROPERTY_NOT_FOUND,
                        "Property not found: " + propertyId));
        if (NotraiusStatuses.PROPERTY_DELETED.equals(statusCodeOf(property))) {
            throw new NotFoundException(NotraiusErrors.PROPERTY_NOT_FOUND,
                    "Property not found: " + propertyId);
        }
        return property;
    }

    /**
     * The standard that governs this world, or a refusal that says which kind of
     * "no" it is: the policy does not exist, it is paused, or it governs another
     * world (a state's own row, or a stateless one another state has shadowed).
     */
    private Policy resolveStandard(UUID policyId, UUID stateId) {
        Optional<Policy> applicable = policyResolver.applicable(policyId, stateId);
        if (applicable.isPresent()) {
            return applicable.get();
        }
        Policy candidate = policyRepository.findById(policyId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.TERM_POLICY_NOT_FOUND,
                        "Policy not found: " + policyId));
        if (!NotraiusStatuses.POLICY_ENABLED.equals(statusCodeOf(candidate))) {
            throw new ConflictException(NotraiusErrors.TERM_POLICY_DISABLED,
                    "Policy '" + candidate.getCode() + "' is " + statusCodeOf(candidate)
                            + "; its values cannot be changed while it is paused");
        }
        throw new ConflictException(NotraiusErrors.TERM_POLICY_NOT_APPLICABLE,
                "Policy '" + candidate.getCode() + "' does not govern this property's state " + stateId);
    }

    private boolean isSelectable(Rule rule, UUID callerId) {
        if (rule.getCreatorId() != null && rule.getCreatorId().equals(callerId)) {
            return true;
        }
        return rule.getVisibility() != null
                && rule.getVisibility().name().equals("PUBLIC");
    }

    private void requireValidValue(Policy policy, BigDecimal value) {
        if (value == null) {
            throw new BadRequestException(NotraiusErrors.TERM_VALUE_INVALID, "A value is required");
        }
        // The lease gate's law, applied one step earlier. A commitment of zero
        // funds nothing, and a count of half a month (or 2.5 units) cannot be
        // turned into money when the matrix is derived - so the number is refused
        // here, where the owner can correct it, instead of at the first lease,
        // after the property is already on the market.
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException(NotraiusErrors.TERM_VALUE_INVALID,
                    "Value " + value + " funds nothing; a standard's value must be greater than zero");
        }
        if (PolicyUnit.UNITS == policy.getUnit() && value.stripTrailingZeros().scale() > 0) {
            throw new BadRequestException(NotraiusErrors.TERM_VALUE_INVALID,
                    "Value " + value + " counts units of the pricing's duration; it must be a whole number");
        }
        if (!withinBounds(policy, value)) {
            throw new BadRequestException(NotraiusErrors.TERM_VALUE_OUT_OF_BOUNDS,
                    "Value " + value + " is outside [" + policy.getMinimum() + ", "
                            + policy.getMaximum() + "] for standard '" + policy.getCode() + "'");
        }
    }

    private boolean withinBounds(Policy policy, BigDecimal value) {
        if (value == null) {
            return false;
        }
        if (policy.getMinimum() != null && value.compareTo(policy.getMinimum()) < 0) {
            return false;
        }
        return policy.getMaximum() == null || value.compareTo(policy.getMaximum()) <= 0;
    }

    private void requireValidWindow(Instant from, Instant to) {
        if (from != null && to != null && !to.isAfter(from)) {
            throw new BadRequestException(NotraiusErrors.TERM_WINDOW_INVALID,
                    "Window end " + to + " is not after its start " + from);
        }
    }

    /** Half-open overlap: two windows collide unless one ends where the other begins. */
    private boolean overlaps(Validity validity, Instant from, Instant to) {
        if (validity == null) {
            return false;
        }
        boolean startsBeforeOtherEnds = to == null
                || validity.getFromDate() == null
                || validity.getFromDate().isBefore(to);
        boolean otherStartsBeforeEnds = validity.getToDate() == null
                || from == null
                || from.isBefore(validity.getToDate());
        return startsBeforeOtherEnds && otherStartsBeforeEnds;
    }

    /**
     * What the selection reads as at an instant — the same clock law a pricing
     * answers to ({@code Windows}), because a selection window is the same kind of
     * promise: it applies between its bounds and not outside them. Reading only
     * the end said a selection made for next season already applies today.
     */
    private String effectiveSelectionStatus(PropertyRuleSelection selection, Instant now) {
        Validity validity = selection.getValidity();
        if (validity == null) {
            return null;
        }
        return Windows.statusIn(
                validity.getStatus() != null ? validity.getStatus().getCode() : null,
                NotraiusStatuses.RULE_SELECTION_EXPIRED,
                validity.getFromDate(), validity.getToDate(), now);
    }

    private String statusCodeOf(Property property) {
        return property.getStatus() != null ? property.getStatus().getCode() : null;
    }

    private String statusCodeOf(Policy policy) {
        return policy.getStatus() != null ? policy.getStatus().getCode() : null;
    }

    private String statusCodeOf(PropertyPolicyValue value) {
        return value.getStatus() != null ? value.getStatus().getCode() : null;
    }

    private String statusCodeOf(Rule rule) {
        return rule.getStatus() != null ? rule.getStatus().getCode() : null;
    }

    private void audit(String activityCode, UUID referenceId, String operation, Object after, Object before) {
        audit(Entities.PROPERTY_POLICIES, activityCode, referenceId, operation, after, before);
    }

    private void audit(String entityCode, String activityCode, UUID referenceId, String operation,
                       Object after, Object before) {
        // Values and selections publish their history (they are the rows the
        // parent's terms door and their own /revisions door read back), so the
        // entry is demanded rather than hoped for.
        auditTrail.recordRequired(entityCode, activityCode, referenceId, operation, before, after);
    }
}
