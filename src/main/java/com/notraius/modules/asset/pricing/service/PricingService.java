package com.notraius.modules.asset.pricing.service;

import com.notraius.common.audit.AuditTrail;
import com.notraius.common.NotraiusErrors;
import com.notraius.common.services.PolicyValueSeeder;
import com.notraius.common.services.RevisionService;
import com.notraius.common.services.StatusResolver;
import com.notraius.database.entities.asset.Pricing;
import com.notraius.database.entities.asset.Property;
import com.notraius.database.entities.asset.PropertyPolicyValue;
import com.notraius.database.entities.config.PolicyUnit;
import com.notraius.database.entities.reference.Duration;
import com.notraius.database.entities.reference.Entities;
import com.notraius.database.entities.reference.Type;
import com.notraius.database.entities.reference.Validity;
import com.notraius.database.repositories.asset.PricingRepository;
import com.notraius.database.repositories.asset.PropertyPolicyValueRepository;
import com.notraius.database.repositories.asset.PropertyRepository;
import com.notraius.database.repositories.reference.DurationRepository;
import com.notraius.database.repositories.reference.TypeRepository;
import com.notraius.database.repositories.reference.ValidityRepository;
import com.notraius.modules.asset.pricing.dto.request.PricingCreateRequest;
import com.notraius.modules.asset.pricing.dto.request.PricingUpdateRequest;
import com.notraius.modules.asset.pricing.dto.request.PricingWindowRequest;
import com.notraius.modules.asset.pricing.dto.response.PricingResponse;
import com.notraius.modules.asset.pricing.mapper.PricingMapper;
import com.notraius.modules.core.common.constants.NotraiusActivities;
import com.notraius.modules.core.common.constants.NotraiusStatuses;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.notraius.common.dto.responses.RevisionSummaryResponse;
import com.notraius.modules.core.common.Windows;
import org.primarion.module.access.consent.service.OwnershipGuard;
import org.primarion.module.infra.webhook.service.WebhookDispatchHelper;
import org.primarion.module.errors.BadRequestException;
import org.primarion.module.errors.ConflictException;
import org.primarion.module.errors.ForbiddenException;
import org.primarion.module.errors.NotFoundException;
import org.primarion.module.utils.security.SecurityAuthorityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The asset module's pricing sub-module (PLAN.md 4.2) — what the market pays.
 * <p>
 * <b>One row per commercial type, forever.</b> A property has at most one
 * NIGHTLY and one MONTHLY row; a price change is not a new row, it is a new
 * window issued against the same row, with a version bump. The row is therefore
 * the price's identity and its history is the versions of it.
 * <p>
 * <b>The window is the answer.</b> A price answers only while its half-open
 * {@code [from, to)} contains the present instant. {@code LIVE} and
 * {@code PAUSED} are what the owner did; {@code EXPIRED} is what the clock says,
 * derived at read time and never stored, because no job should have to wake up
 * to make yesterday's price stop being true.
 * <p>
 * <b>An amount is never zero.</b> Zero is an aberration — a free night is a
 * policy question, not a price — so the amount is strictly positive here, in the
 * entity and in the database.
 * <p>
 * <b>Birth carries the terms.</b> A pricing is born with every mandatory
 * standard already valued at its minimum, because a pricing with holes in its
 * mandatory terms could never be published and the owner was never asked.
 */
@Service
@Slf4j
public class PricingService extends OwnershipGuard {

    private final PropertyRepository propertyRepository;
    private final PricingRepository pricingRepository;
    private final PropertyPolicyValueRepository valueRepository;
    private final TypeRepository typeRepository;
    private final DurationRepository durationRepository;
    private final ValidityRepository validityRepository;
    private final PolicyValueSeeder policyValueSeeder;
    private final StatusResolver statusResolver;
    private final PricingMapper pricingMapper;
    private final AuditTrail auditTrail;
    private final RevisionService revisionService;
    private final WebhookDispatchHelper webhookDispatchHelper;
    private final ObjectMapper objectMapper;

    public PricingService(PropertyRepository propertyRepository,
                          PricingRepository pricingRepository,
                          PropertyPolicyValueRepository valueRepository,
                          TypeRepository typeRepository,
                          DurationRepository durationRepository,
                          ValidityRepository validityRepository,
                          PolicyValueSeeder policyValueSeeder,
                          StatusResolver statusResolver,
                          PricingMapper pricingMapper,
                          AuditTrail auditTrail,
                          RevisionService revisionService,
                          WebhookDispatchHelper webhookDispatchHelper,
                          ObjectMapper objectMapper) {
        this.propertyRepository = propertyRepository;
        this.pricingRepository = pricingRepository;
        this.valueRepository = valueRepository;
        this.typeRepository = typeRepository;
        this.durationRepository = durationRepository;
        this.validityRepository = validityRepository;
        this.policyValueSeeder = policyValueSeeder;
        this.statusResolver = statusResolver;
        this.pricingMapper = pricingMapper;
        this.auditTrail = auditTrail;
        this.revisionService = revisionService;
        this.webhookDispatchHelper = webhookDispatchHelper;
        this.objectMapper = objectMapper;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Create
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Define a pricing. The type must be a live PRICINGS type and must not be
     * priced already; the duration must be live; the amount strictly positive;
     * the window coherent. The property must be anchored, because a price is
     * only meaningful in a currency (which it inherits) and its mandatory terms
     * only exist in a state's world.
     */
    @Transactional
    public PricingResponse createPricing(UUID propertyId, PricingCreateRequest request, UUID callerId) {
        Property property = requireOwnedProperty(propertyId, callerId);
        if (property.getStateId() == null) {
            throw new ConflictException(NotraiusErrors.PROPERTY_STATE_UNRESOLVABLE,
                    "Property " + propertyId + " must be anchored (location → state → currency) before it can be priced");
        }
        Type type = requirePricingType(request.getTypeId());
        if (pricingRepository.existsByPropertyIdAndTypeId(propertyId, type.getId())) {
            throw new ConflictException(NotraiusErrors.PRICING_TYPE_ALREADY_PRICED,
                    "Property " + propertyId + " already prices " + type.getCode()
                            + "; issue a new window on that pricing instead");
        }
        Duration duration = requireLiveDuration(request.getDurationId());
        BigDecimal amount = requirePositiveAmount(request.getAmount());

        Instant now = Instant.now();
        Instant from = request.getFromDate() != null ? request.getFromDate() : now;
        Instant to = request.getToDate();
        requireValidWindow(from, to);

        Validity validity = statusResolver.openValidity(Entities.PRICINGS, NotraiusStatuses.PRICING_LIVE);
        validity.setFromDate(from);
        validity.setToDate(to);
        validityRepository.save(validity);

        Pricing pricing = new Pricing();
        pricing.setProperty(property);
        pricing.setType(type);
        pricing.setDuration(duration);
        pricing.setAmount(amount);
        pricing.setCheckable(request.isCheckable());
        pricing.setValidity(validity);
        pricing.setStatus(statusResolver.resolve(Entities.PRICINGS, NotraiusStatuses.PRICING_LIVE));
        pricing.setVersion(1);
        pricing.setCreatedBy(callerId);
        pricingRepository.save(pricing);

        int seeded = policyValueSeeder.seedMandatory(pricing, callerId);
        log.info("Pricing created: id={} property={} type={} amount={} seededValues={}",
                pricing.getId(), propertyId, type.getCode(), amount, seeded);

        PricingResponse response = pricingMapper.toResponse(pricing, effectiveStatus(pricing, now));
        audit(NotraiusActivities.Pricings.CREATE, pricing.getId(), "CREATE", response, null);
        announce(pricing, "CREATED", NotraiusActivities.Pricings.CREATE);
        return response;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Edit
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Edit the amount, the duration or the checkable flag of an answering price.
     * <p>
     * The duration is the unit the count-valued standards are counted in, so it
     * cannot change once such values exist — "four months" would silently become
     * "four hours". Absent fields are untouched.
     */
    @Transactional
    public PricingResponse updatePricing(UUID pricingId, PricingUpdateRequest request, UUID callerId) {
        Pricing pricing = requireOwnedPricing(pricingId, callerId);
        requireAnswering(pricing);

        // Snapshot BEFORE the first mutation. A version chain is only worth
        // writing if its old side is the old truth: captured after the setters,
        // it would state the new amount as the previous one, and "the price as
        // of version 3" would answer the price of version 4 forever.
        PricingResponse before = pricingMapper.toResponse(pricing, effectiveStatus(pricing, Instant.now()));

        boolean changed = false;
        if (request.getDurationId() != null
                && !Objects.equals(request.getDurationId(), pricing.getDuration().getId())) {
            if (hasCountValuedTerms(pricing.getId())) {
                throw new ConflictException(NotraiusErrors.PRICING_DURATION_CHANGE_BLOCKED,
                        "Pricing " + pricingId + " carries count-valued policies; forget those values first "
                                + "(DELETE /asset/pricings/" + pricingId + "/values/{policyId}), then change its duration");
            }
            pricing.setDuration(requireLiveDuration(request.getDurationId()));
            changed = true;
        }
        if (request.getAmount() != null) {
            BigDecimal amount = requirePositiveAmount(request.getAmount());
            if (amount.compareTo(pricing.getAmount()) != 0) {
                pricing.setAmount(amount);
                changed = true;
            }
        }
        if (request.getCheckable() != null && request.getCheckable() != pricing.isCheckable()) {
            pricing.setCheckable(request.getCheckable());
            changed = true;
        }
        if (!changed) {
            // Nothing was written, so `before` is still the current truth.
            return before;
        }

        pricing.setVersion(pricing.getVersion() + 1);
        pricingRepository.save(pricing);

        PricingResponse after = pricingMapper.toResponse(pricing, effectiveStatus(pricing, Instant.now()));
        audit(NotraiusActivities.Pricings.UPDATE, pricingId, "UPDATE", after, before);
        announce(pricing, "UPDATED", NotraiusActivities.Pricings.UPDATE);
        return after;
    }

    /**
     * Issue a fresh validity against the same row — the only way a price answers
     * again after its window ended, and the normal shape of a planned change.
     * The new window must be coherent; an open end stays open.
     */
    @Transactional
    public PricingResponse issueWindow(UUID pricingId, PricingWindowRequest request, UUID callerId) {
        Pricing pricing = requireOwnedPricing(pricingId, callerId);

        Instant now = Instant.now();
        Instant from = request.getFromDate() != null ? request.getFromDate() : now;
        Instant to = request.getToDate();
        requireValidWindow(from, to);

        PricingResponse before = pricingMapper.toResponse(pricing, effectiveStatus(pricing, now));

        Validity previous = pricing.getValidity();
        Validity validity = statusResolver.openValidity(Entities.PRICINGS, NotraiusStatuses.PRICING_LIVE);
        validity.setFromDate(from);
        validity.setToDate(to);
        validityRepository.save(validity);

        pricing.setValidity(validity);
        pricing.setVersion(pricing.getVersion() + 1);
        pricingRepository.save(pricing);

        if (previous != null) {
            validityRepository.delete(previous);
        }

        PricingResponse after = pricingMapper.toResponse(pricing, effectiveStatus(pricing, now));
        audit(NotraiusActivities.Pricings.WINDOW, pricingId, "WINDOW", after, before);
        announce(pricing, "WINDOW_ISSUED", NotraiusActivities.Pricings.WINDOW);
        return after;
    }

    /** Stop answering without ending the window: the owner's own market switch. */
    @Transactional
    public PricingResponse pausePricing(UUID pricingId, UUID callerId) {
        Pricing pricing = requireOwnedPricing(pricingId, callerId);
        requireStatus(pricing, NotraiusStatuses.PRICING_LIVE, "pause");

        PricingResponse before = pricingMapper.toResponse(pricing, effectiveStatus(pricing, Instant.now()));
        pricing.setStatus(statusResolver.resolve(Entities.PRICINGS, NotraiusStatuses.PRICING_PAUSED));
        pricing.setVersion(pricing.getVersion() + 1);
        pricingRepository.save(pricing);

        PricingResponse after = pricingMapper.toResponse(pricing, effectiveStatus(pricing, Instant.now()));
        audit(NotraiusActivities.Pricings.PAUSE, pricingId, "PAUSE", after, before);
        announce(pricing, "PAUSED", NotraiusActivities.Pricings.PAUSE);
        return after;
    }

    /** Resume answering — only inside a window that still contains now. */
    @Transactional
    public PricingResponse resumePricing(UUID pricingId, UUID callerId) {
        Pricing pricing = requireOwnedPricing(pricingId, callerId);
        requireStatus(pricing, NotraiusStatuses.PRICING_PAUSED, "resume");
        if (NotraiusStatuses.PRICING_EXPIRED.equals(effectiveStatus(pricing, Instant.now()))) {
            throw new ConflictException(NotraiusErrors.PRICING_EXPIRED,
                    "Pricing " + pricingId + " has no window covering now; issue a new validity first");
        }

        PricingResponse before = pricingMapper.toResponse(pricing, effectiveStatus(pricing, Instant.now()));
        pricing.setStatus(statusResolver.resolve(Entities.PRICINGS, NotraiusStatuses.PRICING_LIVE));
        pricing.setVersion(pricing.getVersion() + 1);
        pricingRepository.save(pricing);

        PricingResponse after = pricingMapper.toResponse(pricing, effectiveStatus(pricing, Instant.now()));
        audit(NotraiusActivities.Pricings.RESUME, pricingId, "RESUME", after, before);
        announce(pricing, "RESUMED", NotraiusActivities.Pricings.RESUME);
        return after;
    }

    /**
     * Remove a pricing outright. Its policy values are the property's own story
     * and a lease may already have been born at this price; either keeps the row
     * alive — pausing is the reversible alternative offered instead.
     */
    @Transactional
    public void deletePricing(UUID pricingId, UUID callerId) {
        Pricing pricing = requireOwnedPricing(pricingId, callerId);
        if (valueRepository.existsByPricingId(pricingId)) {
            throw new ConflictException(NotraiusErrors.PRICING_IN_USE,
                    "Pricing " + pricingId + " carries policy values; pause it instead of removing it");
        }
        // Forward hook: a lease born at this price would also keep it alive
        // (PLAN.md 4.2); lease.leases does not exist in this schema yet.

        PricingResponse before = pricingMapper.toResponse(pricing, effectiveStatus(pricing, Instant.now()));
        Validity validity = pricing.getValidity();
        pricingRepository.delete(pricing);
        if (validity != null) {
            validityRepository.delete(validity);
        }
        audit(NotraiusActivities.Pricings.DELETE, pricingId, "DELETE", null, before);
        dispatchPricing(pricing, "DELETED", NotraiusActivities.Pricings.DELETE);
    }

    /**
     * The pricing's public fact, sent to the acting owner's own webhooks: a
     * price that answers, pauses or expires is exactly what a client integrates
     * against. Fail-soft — the helper swallows a dead subscriber.
     */
    private void announce(Pricing pricing, String event, String activityCode) {
        dispatchPricing(pricing, event, activityCode);
    }

    private void dispatchPricing(Pricing pricing, String event, String activityCode) {
        if (webhookDispatchHelper == null) {
            return;
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("event", event);
        payload.put("pricingId", pricing.getId() != null ? pricing.getId().toString() : null);
        payload.put("status", pricing.getStatus() != null ? pricing.getStatus().getCode() : null);
        if (pricing.getProperty() != null) {
            payload.put("propertyId", pricing.getProperty().getId().toString());
        }
        if (pricing.getType() != null) {
            payload.put("type", pricing.getType().getCode());
        }
        payload.put("amount", pricing.getAmount() != null ? pricing.getAmount().toPlainString() : null);
        // The dispatcher resolves the activity by FAMILY code (the registry
        // row "PRICING", not the pair "PRICING.CREATE") — the same rule the
        // lease announcer and every peer host follow.
        int dot = activityCode.indexOf('.');
        String family = dot > 0 ? activityCode.substring(0, dot) : activityCode;
        webhookDispatchHelper.dispatchToCaller(family, event, payload);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Read
    // ═══════════════════════════════════════════════════════════════════════

    /** The property's pricings. A published property shows them to anyone. */
    @Transactional(readOnly = true)
    public List<PricingResponse> listPricings(UUID propertyId, UUID callerId) {
        Property property = requireReadableProperty(propertyId, callerId);
        Instant now = Instant.now();
        return pricingRepository.findByPropertyId(property.getId()).stream()
                .map(pricing -> pricingMapper.toResponse(pricing, effectiveStatus(pricing, now)))
                .toList();
    }

    /** The current truth — the shape every client already reads. */
    @Transactional(readOnly = true)
    public PricingResponse getPricing(UUID pricingId, UUID callerId) {
        return getPricing(pricingId, callerId, null);
    }

    /**
     * The price as one version stated it.
     * <p>
     * The row holds only its newest truth, so a past version is reconstructed
     * from the audit chain ({@link RevisionService}) in this DTO's own shape.
     * {@code version} absent or equal to the row's own current version is the
     * live read — no chain lookup, no extra query.
     * <p>
     * <b>The past is the owner's and moderation's.</b> A price that answered in
     * March is not the price answering now, and serving it on the public door
     * would let a visitor read a number the listing no longer stands behind.
     * Anyone else asking for a past revision gets the same 404 an absent row
     * gets, so history never becomes a second existence oracle.
     */
    @Transactional(readOnly = true)
    public PricingResponse getPricing(UUID pricingId, UUID callerId, Integer version) {
        Pricing pricing = pricingRepository.findById(pricingId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.PRICING_NOT_FOUND,
                        "Pricing not found: " + pricingId));
        Property property = requireReadableProperty(pricing.getProperty().getId(), callerId);
        if (version == null || version.equals(pricing.getVersion())) {
            return pricingMapper.toResponse(pricing, effectiveStatus(pricing, Instant.now()));
        }
        requireHistoryReader(property, callerId, pricingId);
        PricingResponse revision = revisionService.revisionOf(
                Entities.PRICINGS, pricingId, version, PricingResponse.class);
        revision.setStatusCode(effectiveStatus(revision, Instant.now()));
        return revision;
    }

    /**
     * Which revisions of this price the chain holds — discovery, so a client never
     * has to learn the shape of a price's past by reading refusals. Same audience
     * as the single revision: the owner and moderation.
     */
    @Transactional(readOnly = true)
    public List<RevisionSummaryResponse> listRevisions(UUID pricingId, UUID callerId) {
        Pricing pricing = pricingRepository.findById(pricingId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.PRICING_NOT_FOUND,
                        "Pricing not found: " + pricingId));
        Property property = requireReadableProperty(pricing.getProperty().getId(), callerId);
        requireHistoryReader(property, callerId, pricingId);
        return revisionService.revisionsOf(Entities.PRICINGS, pricingId, pricing.getVersion());
    }

    /**
     * The prices of one property that <b>answered at an instant</b> — "what did a
     * night cost on 3 March", answered for the whole page at once.
     * <p>
     * <b>Why it cannot be the list door with a date.</b> A row's window is
     * rewritten in place when a new validity is issued, so today's rows cannot say
     * what an expired window said before it was replaced. The answer comes from
     * the chain ({@link RevisionService#statesAt}) — the plan's own mechanism for
     * "what did a night cost last August" — and the current row only supplies the
     * set of records to ask about (a price removed since then keeps its history
     * reachable by id through {@code ?version=N}, but is not replayed here;
     * removal is refused while anything depends on the row, so that is a rare,
     * deliberate act rather than the normal end of a price).
     * <p>
     * <b>Answering is the plan's three facts, applied at the instant asked for:</b>
     * the window must contain it ({@code Windows}, half-open, so a handover at
     * midnight belongs to the successor), the owner's act must be LIVE rather than
     * PAUSED, and the state must exist — a price born later, or already removed by
     * then, does not answer. The status is re-derived <i>at that instant</i>, so
     * every row returned reads LIVE: that is what answering means.
     * <p>
     * Visibility is the current property's: a published property shows its prices
     * to anyone, a draft answers 404, exactly as the live list does.
     */
    @Transactional(readOnly = true)
    public List<PricingResponse> answeringAt(UUID propertyId, UUID typeId, Instant at, UUID callerId) {
        if (at == null) {
            throw new BadRequestException(NotraiusErrors.PRICING_WINDOW_INVALID,
                    "An as-of read needs the instant to read as of");
        }
        Property property = requireReadableProperty(propertyId, callerId);
        List<Pricing> pricings = pricingRepository.findByPropertyId(property.getId());
        if (pricings.isEmpty()) {
            return List.of();
        }
        Map<UUID, PricingResponse> states = revisionService.statesAt(Entities.PRICINGS,
                pricings.stream().map(Pricing::getId).toList(), at, PricingResponse.class);
        return states.values().stream()
                .filter(state -> typeId == null || typeId.equals(state.getTypeId()))
                .filter(state -> NotraiusStatuses.PRICING_LIVE.equals(effectiveStatus(state, at)))
                .peek(state -> state.setStatusCode(NotraiusStatuses.PRICING_LIVE))
                .sorted(Comparator.comparing(PricingResponse::getTypeCode,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(PricingResponse::getId))
                .toList();
    }

    /** Owner or staff; everyone else is told the record does not exist. */
    private void requireHistoryReader(Property property, UUID callerId, UUID pricingId) {
        if (SecurityAuthorityUtils.currentIsStaff()) {
            return;
        }
        try {
            requireOwnership("Only the property's owner may read a price's history", property, callerId);
        } catch (ForbiddenException e) {
            throw new NotFoundException(NotraiusErrors.PRICING_NOT_FOUND,
                    "Pricing not found: " + pricingId);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // OwnershipGuard — the property's owner
    // ═══════════════════════════════════════════════════════════════════════

    /** args[0]: the property, args[1]: the caller id. One property, one owner. */
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

    /**
     * The status a pricing reads as at an instant. The clock speaks first: a row
     * whose window does not contain now is EXPIRED whatever the owner last did,
     * because expiry is the one fact that cannot be undone by an earlier act.
     * Inside a live window, the stored act (LIVE or PAUSED) is the answer.
     * <p>
     * The law itself lives in {@code Windows}, shared with the rule selections
     * and the lease's answering-pricing gate, so the three cannot drift apart.
     */
    private String effectiveStatus(Pricing pricing, Instant now) {
        Validity validity = pricing.getValidity();
        return Windows.statusIn(storedStatus(pricing), NotraiusStatuses.PRICING_EXPIRED,
                validity != null ? validity.getFromDate() : null,
                validity != null ? validity.getToDate() : null, now);
    }

    /**
     * The same law applied to a revision — the row as the chain recorded it.
     * <p>
     * A snapshot freezes the window and the owner's act, not the clock: asking for
     * version 3 must give the answer the live door would give for version 3, at
     * the instant of <i>this</i> read. Without this the same revision reads LIVE
     * through the history door and EXPIRED through the live one, which is exactly
     * the kind of two-truths-one-resource confusion the versioning law exists to
     * prevent.
     * <p>
     * Snapshots written before {@code storedStatusCode} existed carry only the
     * derived word; those fall back to it, mapping the one ambiguous case (a
     * frozen EXPIRED whose window answers now) to LIVE — the act the clock's word
     * cannot express.
     */
    private String effectiveStatus(PricingResponse revision, Instant now) {
        String stored = revision.getStoredStatusCode() != null
                ? revision.getStoredStatusCode()
                : (NotraiusStatuses.PRICING_EXPIRED.equals(revision.getStatusCode())
                        ? NotraiusStatuses.PRICING_LIVE
                        : revision.getStatusCode());
        return Windows.statusIn(stored, NotraiusStatuses.PRICING_EXPIRED,
                revision.getFromDate(), revision.getToDate(), now);
    }

    private String storedStatus(Pricing pricing) {
        return pricing.getStatus() != null ? pricing.getStatus().getCode() : null;
    }

    private void requireAnswering(Pricing pricing) {
        if (NotraiusStatuses.PRICING_EXPIRED.equals(effectiveStatus(pricing, Instant.now()))) {
            throw new ConflictException(NotraiusErrors.PRICING_EXPIRED,
                    "Pricing " + pricing.getId() + " answers no instant now; issue a new validity first");
        }
    }

    private Pricing requireOwnedPricing(UUID pricingId, UUID callerId) {
        Pricing pricing = pricingRepository.findById(pricingId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.PRICING_NOT_FOUND,
                        "Pricing not found: " + pricingId));
        requireOwnership("Only the property's owner may change its pricings", pricing.getProperty(), callerId);
        if (NotraiusStatuses.PROPERTY_SUSPENDED.equals(statusCodeOf(pricing.getProperty()))) {
            throw new ConflictException(NotraiusErrors.PROPERTY_INVALID_STATE,
                    "Property " + pricing.getProperty().getId() + " is suspended; its prices cannot change");
        }
        return pricing;
    }

    /** A suspended property is frozen, prices included: moderation's stop is not editable around. */
    private Property requireOwnedProperty(UUID propertyId, UUID callerId) {
        Property property = requireProperty(propertyId);
        requireOwnership("Only the property's owner may change its pricings", property, callerId);
        if (NotraiusStatuses.PROPERTY_SUSPENDED.equals(statusCodeOf(property))) {
            throw new ConflictException(NotraiusErrors.PROPERTY_INVALID_STATE,
                    "Property " + propertyId + " is suspended; its prices cannot change");
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
            requireOwnership("Only the property's owner may read its pricings", property, callerId);
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

    private Type requirePricingType(UUID typeId) {
        Type type = typeRepository.findById(typeId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.PRICING_TYPE_NOT_FOUND,
                        "Type not found: " + typeId));
        String entityCode = type.getEntity() != null ? type.getEntity().getCode() : null;
        if (!Entities.PRICINGS.equals(entityCode)) {
            throw new BadRequestException(NotraiusErrors.PRICING_TYPE_ENTITY_MISMATCH,
                    "Type '" + type.getCode() + "' belongs to " + entityCode + ", not PRICINGS");
        }
        if (!NotraiusStatuses.TYPE_ACTIVE.equals(statusCodeOf(type))) {
            throw new ConflictException(NotraiusErrors.PRICING_TYPE_NOT_LIVE,
                    "Type '" + type.getCode() + "' is " + statusCodeOf(type));
        }
        return type;
    }

    private Duration requireLiveDuration(UUID durationId) {
        Duration duration = durationRepository.findById(durationId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.PRICING_DURATION_NOT_FOUND,
                        "Duration not found: " + durationId));
        if (duration.getStatus() == null
                || !NotraiusStatuses.TYPE_ACTIVE.equals(duration.getStatus().getCode())) {
            throw new ConflictException(NotraiusErrors.PRICING_DURATION_NOT_LIVE,
                    "Duration " + durationId + " is not ACTIVE");
        }
        return duration;
    }

    private BigDecimal requirePositiveAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException(NotraiusErrors.PRICING_AMOUNT_INVALID,
                    "Amount " + amount + " is not strictly positive");
        }
        return amount;
    }

    private void requireValidWindow(Instant from, Instant to) {
        if (from != null && to != null && !to.isAfter(from)) {
            throw new BadRequestException(NotraiusErrors.PRICING_WINDOW_INVALID,
                    "Window end " + to + " is not after its start " + from);
        }
    }

    private void requireStatus(Pricing pricing, String expected, String operation) {
        String actual = pricing.getStatus() != null ? pricing.getStatus().getCode() : null;
        if (!expected.equals(actual)) {
            throw new ConflictException(NotraiusErrors.PRICING_INVALID_STATE,
                    "Pricing is " + actual + "; " + operation + " requires " + expected);
        }
    }

    private boolean hasCountValuedTerms(UUID pricingId) {
        List<PropertyPolicyValue> values = valueRepository.findByPricingId(pricingId);
        return values.stream().anyMatch(value -> value.getPolicy() != null
                && value.getPolicy().getUnit() == PolicyUnit.UNITS);
    }

    private String statusCodeOf(Property property) {
        return property.getStatus() != null ? property.getStatus().getCode() : null;
    }

    private String statusCodeOf(Type type) {
        return type.getStatus() != null ? type.getStatus().getCode() : null;
    }

    /**
     * A pricing's history is a read model, not a shadow: the entry is written
     * through {@link RequiredAuditWriter#writeRequired}, so a failure rolls the
     * version bump back with it ({@code ?version=N} can never name a revision
     * the chain does not hold).
     */
    private void audit(String activityCode, UUID referenceId, String operation, Object after, Object before) {
        auditTrail.recordRequired(Entities.PRICINGS, activityCode, referenceId, operation, before, after);
    }
}
