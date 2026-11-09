package com.notraius.modules.asset.property.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.notraius.common.audit.AuditTrail;
import com.notraius.common.NotraiusErrors;
import com.notraius.common.integration.LogisticLocationClient;
import com.notraius.common.integration.StateCurrencyResolver;
import com.notraius.common.services.QueryFilters;
import com.notraius.common.services.RevisionService;
import com.notraius.common.services.StatusResolver;
import com.notraius.config.properties.NotraiusProperties;
import com.notraius.database.entities.asset.Property;
import com.notraius.database.entities.asset.PropertyAmenity;
import com.notraius.database.entities.lease.Lease;
import com.notraius.database.entities.reference.Amenity;
import com.notraius.database.entities.reference.Entities;
import com.notraius.database.entities.reference.Type;
import com.notraius.database.repositories.asset.PricingRepository;
import com.notraius.database.repositories.asset.PropertyAmenityRepository;
import com.notraius.database.repositories.asset.PropertyRepository;
import com.notraius.database.repositories.reference.AmenityRepository;
import com.notraius.database.repositories.reference.TypeRepository;
import com.notraius.database.repositories.visit.VisitRepository;
import com.notraius.modules.asset.property.dto.request.PropertyAmenityRequest;
import com.notraius.modules.asset.property.dto.request.PropertyCreateRequest;
import com.notraius.modules.asset.property.dto.request.PropertyLocationRequest;
import com.notraius.modules.asset.property.dto.request.PropertyUpdateRequest;
import com.notraius.modules.asset.property.dto.response.PropertyAmenityResponse;
import com.notraius.modules.asset.property.dto.response.PropertyResponse;
import com.notraius.modules.asset.property.dto.response.TenancyResponse;
import com.notraius.modules.asset.property.mapper.PropertyMapper;
import com.notraius.modules.asset.terms.service.TermsService;
import com.notraius.modules.visit.visits.service.VisitService;
import com.notraius.modules.core.common.constants.NotraiusActivities;
import com.notraius.modules.core.common.constants.NotraiusStatuses;
import com.notraius.modules.core.common.schedule.SweepLedger;
import lombok.extern.slf4j.Slf4j;
import com.notraius.common.dto.responses.RevisionSummaryResponse;
import org.primarion.module.access.consent.service.OwnershipGuard;
import org.primarion.module.errors.BadRequestException;
import org.primarion.module.errors.ConflictException;
import org.primarion.module.errors.ForbiddenException;
import org.primarion.module.errors.NotFoundException;
import org.primarion.module.utils.security.SecurityAuthorityUtils;
import org.primarion.module.utils.security.users.UserClient;
import org.primarion.module.utils.security.users.UserService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The asset module's property sub-module (PLAN.md 4.1) — the thing itself.
 * <p>
 * <b>Save first, complete later.</b> A property is born DRAFT from a title and
 * nobody else can see it. It becomes visible only when its owner publishes it,
 * and publishing is a claim ("my listing is ready"), so the system verifies the
 * claim instead of trusting it: a type, the anchors, a price that answers now,
 * and every mandatory standard valued.
 * <p>
 * <b>The anchors are derived once and then frozen.</b> The owner chooses a
 * <em>location</em>; logistics says which state it sits in; the state's country
 * says which currency the property speaks. The client never sends a state or a
 * currency and cannot edit either afterwards — a listing that silently changed
 * money would be a listing nobody could trust. Setting the location is therefore
 * its own one-time act, refused the second time by name.
 * <p>
 * <b>KYC gates creation.</b> A property is a market participant, so creating one
 * requires a verified identity. If the KYC provider cannot be reached the gate
 * fails closed — an unverifiable owner is not a verified one.
 * <p>
 * <b>Statuses are acts, and only some of them are the owner's.</b> DRAFT →
 * PUBLISHED (against the completeness gate) → PAUSED is the owner's own arc;
 * SUSPENDED is moderation's, and moderation does not hand ownership around. A
 * property is never hard-deleted: DELETED is the owner's soft exit, refused only
 * while a live visit or lease depends on it.
 */
@Service
@Slf4j
public class PropertyService extends OwnershipGuard {

    /** The visit statuses that mean "money is held and something still has to happen". */
    private static final java.util.List<String> LIVE_VISIT_STATUSES = java.util.List.of(
            NotraiusStatuses.VISIT_UNPAID,
            NotraiusStatuses.VISIT_REQUESTED,
            NotraiusStatuses.VISIT_ASSIGNED,
            NotraiusStatuses.VISIT_PLANNING,
            NotraiusStatuses.VISIT_CONFIRMED,
            NotraiusStatuses.VISIT_STARTED);

    private final PropertyRepository propertyRepository;
    private final PricingRepository pricingRepository;
    private final PropertyAmenityRepository amenityHoldingRepository;
    private final AmenityRepository amenityRepository;
    private final TypeRepository typeRepository;
    private final VisitRepository visitRepository;
    private final com.notraius.database.repositories.lease.LeaseRepository leaseRepository;
    private final VisitService visitService;
    private final TermsService termsService;
    private final LogisticLocationClient logisticLocationClient;
    private final StateCurrencyResolver stateCurrencyResolver;
    private final UserService userService;
    private final StatusResolver statusResolver;
    private final PropertyMapper propertyMapper;
    private final AuditTrail auditTrail;
    private final NotraiusProperties properties;
    private final QueryFilters queryFilters;
    private final RevisionService revisionService;

    public PropertyService(PropertyRepository propertyRepository,
                           PricingRepository pricingRepository,
                           PropertyAmenityRepository amenityHoldingRepository,
                           AmenityRepository amenityRepository,
                           TypeRepository typeRepository,
                           VisitRepository visitRepository,
                           com.notraius.database.repositories.lease.LeaseRepository leaseRepository,
                           VisitService visitService,
                           TermsService termsService,
                           LogisticLocationClient logisticLocationClient,
                           StateCurrencyResolver stateCurrencyResolver,
                           UserService userService,
                           StatusResolver statusResolver,
                           PropertyMapper propertyMapper,
                           AuditTrail auditTrail,
                           NotraiusProperties properties,
                           QueryFilters queryFilters,
                           RevisionService revisionService) {
        this.propertyRepository = propertyRepository;
        this.pricingRepository = pricingRepository;
        this.amenityHoldingRepository = amenityHoldingRepository;
        this.amenityRepository = amenityRepository;
        this.typeRepository = typeRepository;
        this.visitRepository = visitRepository;
        this.leaseRepository = leaseRepository;
        this.visitService = visitService;
        this.termsService = termsService;
        this.logisticLocationClient = logisticLocationClient;
        this.stateCurrencyResolver = stateCurrencyResolver;
        this.userService = userService;
        this.statusResolver = statusResolver;
        this.propertyMapper = propertyMapper;
        this.auditTrail = auditTrail;
        this.properties = properties;
        this.queryFilters = queryFilters;
        this.revisionService = revisionService;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Create / edit
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Save a property. KYC is checked first — the identity gate exists before
     * anything is written, so a refused call leaves nothing behind.
     */
    @Transactional
    public PropertyResponse createProperty(PropertyCreateRequest request, UUID callerId) {
        requireKyc(callerId);

        Property property = new Property();
        property.setTitle(request.getTitle().trim());
        property.setDescription(trimToNull(request.getDescription()));
        property.setOwnerId(callerId);
        property.setType(request.getTypeId() != null ? requirePropertyType(request.getTypeId()) : null);
        property.setStatus(statusResolver.resolve(Entities.PROPERTY, NotraiusStatuses.PROPERTY_DRAFT));
        property.setVersion(1);
        property.setCreatedBy(callerId);
        propertyRepository.save(property);

        log.info("Property created: id={} owner={}", property.getId(), callerId);
        PropertyResponse response = propertyMapper.toResponse(property);
        audit(NotraiusActivities.Properties.CREATE, property.getId(), "CREATE", response, null);
        return response;
    }

    /**
     * Edit the content. The type may be changed while drafting, but on a
     * published property the change re-runs the completeness gate: the type
     * decides which standards apply, and a published listing may not fall out of
     * compliance by a quiet edit.
     */
    @Transactional
    public PropertyResponse updateProperty(UUID propertyId, PropertyUpdateRequest request, UUID callerId) {
        Property property = requireOwnedProperty(propertyId, callerId);

        // Snapshot BEFORE the first mutation: the chain's old side has to be the
        // old truth, or "the listing as of version 3" answers version 4's text.
        PropertyResponse before = propertyMapper.toResponse(property);

        boolean changed = false;
        if (request.getTitle() != null && !request.getTitle().isBlank()) {
            property.setTitle(request.getTitle().trim());
            changed = true;
        }
        if (request.getDescription() != null) {
            property.setDescription(trimToNull(request.getDescription()));
            changed = true;
        }
        boolean typeChanged = false;
        if (request.getTypeId() != null && (property.getType() == null
                || !request.getTypeId().equals(property.getType().getId()))) {
            property.setType(requirePropertyType(request.getTypeId()));
            typeChanged = true;
            changed = true;
        }
        if (!changed) {
            return before;
        }
        if (typeChanged && isPublished(property)) {
            requireComplete(property);
        }

        property.setVersion(property.getVersion() + 1);
        propertyRepository.save(property);

        PropertyResponse after = propertyMapper.toResponse(property);
        audit(NotraiusActivities.Properties.UPDATE, propertyId, "UPDATE", after, before);
        return after;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // The anchors — location → state → currency, once
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Anchor the property. This is the one act that decides where the property
     * is and what money it speaks, and it happens exactly once:
     * <ul>
     *   <li>the location is resolved through logistics — an unknown location is
     *       refused, never stored as an opaque orphan;</li>
     *   <li>the state comes from that answer, never from the client;</li>
     *   <li>the currency comes from the state's country, never from the client.</li>
     * </ul>
     * All three are written together or none is: the database CHECK enforces the
     * same invariant, so no later code path can leave a half-anchored property.
     */
    @Transactional
    public PropertyResponse setLocation(UUID propertyId, PropertyLocationRequest request, UUID callerId) {
        Property property = requireOwnedProperty(propertyId, callerId);
        if (property.getLocationId() != null) {
            throw new ConflictException(NotraiusErrors.PROPERTY_LOCATION_IMMUTABLE,
                    "Property " + propertyId + " is already anchored at location "
                            + property.getLocationId() + "; the anchors never change");
        }

        UUID locationId = request.getLocationId();
        UUID stateId = logisticLocationClient.resolveStateId(locationId)
                .orElseThrow(() -> new BadRequestException(NotraiusErrors.PROPERTY_LOCATION_UNRESOLVED,
                        "Location " + locationId + " could not be resolved by the logistics platform"));
        UUID currencyId = stateCurrencyResolver.currencyForState(stateId)
                .orElseThrow(() -> new ConflictException(NotraiusErrors.PROPERTY_CURRENCY_UNRESOLVED,
                        "No currency resolves for state " + stateId
                                + "; the property cannot be anchored without one"));

        PropertyResponse before = propertyMapper.toResponse(property);
        property.setLocationId(locationId);
        property.setStateId(stateId);
        property.setCurrencyId(currencyId);
        property.setVersion(property.getVersion() + 1);
        propertyRepository.save(property);

        PropertyResponse after = propertyMapper.toResponse(property);
        audit(NotraiusActivities.Properties.LOCATION, propertyId, "LOCATION", after, before);
        log.info("Property {} anchored: location={} state={} currency={}",
                propertyId, locationId, stateId, currencyId);
        return after;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Lifecycle — the owner's arc and moderation's
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Publish: the owner's claim that the listing is complete, verified against
     * the gate before the market sees it.
     */
    @Transactional
    public PropertyResponse publishProperty(UUID propertyId, UUID callerId) {
        Property property = requireOwnedProperty(propertyId, callerId);
        requireKyc(callerId);
        // DISABLED is the settlement's word, never a lock: the freed property
        // waits for exactly this click (PLAN 6.0/6.7).
        requireStatusIn(property, "publish",
                NotraiusStatuses.PROPERTY_DRAFT, NotraiusStatuses.PROPERTY_PAUSED,
                NotraiusStatuses.PROPERTY_DISABLED);
        requireComplete(property);

        PropertyResponse before = propertyMapper.toResponse(property);
        property.setStatus(statusResolver.resolve(Entities.PROPERTY, NotraiusStatuses.PROPERTY_PUBLISHED));
        property.setVersion(property.getVersion() + 1);
        propertyRepository.save(property);

        PropertyResponse after = propertyMapper.toResponse(property);
        audit(NotraiusActivities.Properties.PUBLISH, propertyId, "PUBLISH", after, before);
        return after;
    }

    /** Unpublish: the owner's own pause. Reversible, and the terms stay intact. */
    @Transactional
    public PropertyResponse unpublishProperty(UUID propertyId, UUID callerId) {
        Property property = requireOwnedProperty(propertyId, callerId);
        // A tenancy is the listing's living truth (PLAN 6.0): the pause button is
        // for market-facing states, not for someone's home.
        if (NotraiusStatuses.PROPERTY_LEASED.equals(statusCodeOf(property))) {
            throw new ConflictException(NotraiusErrors.PROPERTY_IN_USE,
                    "Property " + propertyId + " is LEASED by a live tenancy and cannot be unpublished");
        }
        requireStatusIn(property, "unpublish", NotraiusStatuses.PROPERTY_PUBLISHED);

        // Owner-fault wind-down (PLAN 5.4): every visit this listing still owes
        // money for is refunded in full, with no penalty — and because the owner
        // chose to withdraw a commitment, their counterpart carries the judgment.
        visitService.windDown(propertyId, true, "owner unpublished the property");

        PropertyResponse before = propertyMapper.toResponse(property);
        property.setStatus(statusResolver.resolve(Entities.PROPERTY, NotraiusStatuses.PROPERTY_PAUSED));
        property.setVersion(property.getVersion() + 1);
        propertyRepository.save(property);

        PropertyResponse after = propertyMapper.toResponse(property);
        audit(NotraiusActivities.Properties.UNPUBLISH, propertyId, "UNPUBLISH", after, before);
        return after;
    }

    /**
     * Moderation's stop. A suspended property leaves the market at once and the
     * owner cannot publish, delete or edit around it — reinstatement is staff's
     * call, which is the whole point of the state.
     */
    @Transactional
    public PropertyResponse suspendProperty(UUID propertyId, String reason) {
        Property property = requireProperty(propertyId);
        requireStatusIn(property, "suspend", NotraiusStatuses.PROPERTY_DRAFT,
                NotraiusStatuses.PROPERTY_PUBLISHED, NotraiusStatuses.PROPERTY_PAUSED);

        // Staff shutdown (PLAN 5.4): the visitors' money returns in full, but
        // nobody is judged — this is a platform decision, not an owner's cancel.
        visitService.windDown(propertyId, false, "property suspended by moderation");

        PropertyResponse before = propertyMapper.toResponse(property);
        property.setStatus(statusResolver.resolve(Entities.PROPERTY, NotraiusStatuses.PROPERTY_SUSPENDED));
        property.setVersion(property.getVersion() + 1);
        propertyRepository.save(property);

        PropertyResponse after = propertyMapper.toResponse(property);
        audit(NotraiusActivities.Properties.SUSPEND, propertyId, "SUSPEND", after, before);
        log.info("Property {} suspended: {}", propertyId, reason);
        return after;
    }

    /** Reinstatement lands in PAUSED, not PUBLISHED: the owner re-publishes on purpose. */
    @Transactional
    public PropertyResponse reinstateProperty(UUID propertyId) {
        Property property = requireProperty(propertyId);
        requireStatusIn(property, "reinstate", NotraiusStatuses.PROPERTY_SUSPENDED);

        PropertyResponse before = propertyMapper.toResponse(property);
        property.setStatus(statusResolver.resolve(Entities.PROPERTY, NotraiusStatuses.PROPERTY_PAUSED));
        property.setVersion(property.getVersion() + 1);
        propertyRepository.save(property);

        PropertyResponse after = propertyMapper.toResponse(property);
        audit(NotraiusActivities.Properties.REINSTATE, propertyId, "REINSTATE", after, before);
        return after;
    }

    /**
     * The owner's exit: DELETED, never a physical delete. The row keeps the
     * property's story (its prices, its history) while leaving every page, and a
     * suspended property cannot be deleted around moderation.
     * <p>
     * <b>The deletion detaches what the property was holding</b> (PLAN.md 4.1): its
     * rule selections and its amenity holdings go with it, each an entry of its
     * own in the chain. Without that release the catalogue would keep a lock
     * nobody can lift - the in-use gates count holdings regardless of the
     * property's status, and the doors that would release them answer 404 for a
     * deleted property. A locked rule or amenity is the catalogue's loss, not the
     * owner's, so the property releases them on its way out.
     * <p>
     * Prices and their values stay: they are the property's own story, and the
     * pricing's in-use gate is the answer for live tenancies, not deletion.
     * <p>
     * DELETED is reachable from every state the machine itself produces - DISABLED
     * included, because a tenancy's settlement leaves the property there, and
     * "the home I no longer let" must be deletable without re-publishing it to the
     * market first.
     */
    @Transactional
    public void deleteProperty(UUID propertyId, UUID callerId) {
        Property property = requireOwnedProperty(propertyId, callerId);
        requireStatusIn(property, "delete", NotraiusStatuses.PROPERTY_DRAFT,
                NotraiusStatuses.PROPERTY_PUBLISHED, NotraiusStatuses.PROPERTY_PAUSED,
                NotraiusStatuses.PROPERTY_DISABLED);
        // A visit still in flight keeps the property alive: money is held against
        // a listing that must keep resolving (PLAN 4.1). The lease half of this
        // gate lands with lease.leases.
        long liveVisits = visitRepository.countLiveForProperty(propertyId, LIVE_VISIT_STATUSES);
        // The lease half of the gate (PLAN 6.0): a tenancy keeps the listing
        // alive exactly like an in-flight visit, because a home someone lives in
        // must keep resolving.
        if (leaseRepository.countLiveForProperty(propertyId, NotraiusStatuses.LEASE_LIVE_STATUSES) > 0) {
            throw new ConflictException(NotraiusErrors.PROPERTY_IN_USE,
                    "Property " + propertyId + " carries a live lease and cannot be deleted");
        }
        if (liveVisits > 0) {
            throw new ConflictException(NotraiusErrors.PROPERTY_IN_USE,
                    "Property " + propertyId + " has " + liveVisits
                            + " visit(s) in flight and cannot be deleted");
        }

        int releasedRules = termsService.releaseSelections(propertyId);
        List<PropertyAmenity> holdings = amenityHoldingRepository.findByPropertyId(propertyId);
        for (PropertyAmenity holding : holdings) {
            PropertyAmenityResponse beforeHolding = propertyMapper.toAmenityResponse(holding);
            UUID holdingId = holding.getId();
            amenityHoldingRepository.delete(holding);
            audit(NotraiusActivities.Properties.AMENITY_DELETE, holdingId, "DELETE",
                    null, beforeHolding);
        }

        PropertyResponse before = propertyMapper.toResponse(property);
        property.setStatus(statusResolver.resolve(Entities.PROPERTY, NotraiusStatuses.PROPERTY_DELETED));
        property.setVersion(property.getVersion() + 1);
        propertyRepository.save(property);

        log.info("Property deleted: id={} ({} rule selection(s) and {} amenity holding(s) released)",
                propertyId, releasedRules, holdings.size());
        audit(NotraiusActivities.Properties.DELETE, propertyId, "DELETE",
                propertyMapper.toResponse(property), before);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Read
    // ═══════════════════════════════════════════════════════════════════════

    /** The market page: PUBLISHED only, whatever the caller. */
    @Transactional(readOnly = true)
    public Page<PropertyResponse> listPublished(UUID typeId, UUID stateId, String search, Pageable pageable) {
        return propertyRepository.search(null, NotraiusStatuses.PROPERTY_PUBLISHED, typeId, stateId,
                        queryFilters.search(search), pageable)
                .map(propertyMapper::toResponse);
    }

    /** The owner's page: every status of the caller's own rows, filtered by a real one. */
    @Transactional(readOnly = true)
    public Page<PropertyResponse> listMine(UUID callerId, String statusCode, String search, Pageable pageable) {
        return propertyRepository.search(callerId, queryFilters.status(Entities.PROPERTY, statusCode),
                        null, null, queryFilters.search(search), pageable)
                .map(propertyMapper::toResponse);
    }

    /** A published property is public; anything else does not exist for others. */
    @Transactional(readOnly = true)
    public PropertyResponse getProperty(UUID propertyId, UUID callerId) {
        return getProperty(propertyId, callerId, null);
    }

    /**
     * The property as one version stated it.
     * <p>
     * The row holds only its newest truth (that is the storage law: one listing,
     * one row, {@code version} naming which truth), so a past version is
     * reconstructed from the audit chain in this DTO's own shape. {@code version}
     * absent or equal to the row's current version is the live read.
     * <p>
     * <b>History is the owner's and moderation's.</b> A past revision can state
     * what the present row no longer does — the draft title from before
     * publication, the description that was withdrawn — so the public door's own
     * visibility rule is not enough: anyone else gets the same 404 an absent
     * listing gets.
     */
    @Transactional(readOnly = true)
    public PropertyResponse getProperty(UUID propertyId, UUID callerId, Integer version) {
        Property property = requireProperty(propertyId);
        requireUnpublishedInvisible(property, callerId);
        if (version == null || version.equals(property.getVersion())) {
            return propertyMapper.toResponse(property);
        }
        requireHistoryReader(property, callerId);
        return revisionService.revisionOf(Entities.PROPERTY, propertyId, version, PropertyResponse.class);
    }

    /**
     * Which revisions of this listing the chain holds — discovery, so a client
     * never learns the shape of a property's past by asking and reading refusals.
     * The audience is the single revision's own: the owner and moderation.
     */
    @Transactional(readOnly = true)
    public List<RevisionSummaryResponse> listRevisions(UUID propertyId, UUID callerId) {
        Property property = requireProperty(propertyId);
        requireUnpublishedInvisible(property, callerId);
        requireHistoryReader(property, callerId);
        return revisionService.revisionsOf(Entities.PROPERTY, propertyId, property.getVersion());
    }

    /** Owner or staff; everyone else is told the listing does not exist. */
    private void requireHistoryReader(Property property, UUID callerId) {
        if (SecurityAuthorityUtils.currentIsStaff()) {
            return;
        }
        try {
            requireOwnership("Only the property's owner may read its past revisions", property, callerId);
        } catch (ForbiddenException e) {
            throw new NotFoundException(NotraiusErrors.PROPERTY_NOT_FOUND,
                    "Property not found: " + property.getId());
        }
    }

    /**
     * The public occupancy window (UPGRADE.md D2 / I5): "someone lives here,
     * until &lt;date&gt;". Dates only, and the <b>current</b> tenancy only — a
     * dates-only history would hand every buyer the list of who lived here
     * before, so the projection stops at the live window.
     * <p>
     * A property with no live lease still answers with its status and an empty
     * window rather than 404: the property door behind this one already decided
     * whether the row exists, and a second oracle for "is it rented" is not
     * worth a different answer code.
     */
    @Transactional(readOnly = true)
    public TenancyResponse readTenancy(UUID propertyId, UUID callerId) {
        Property property = requireReadableProperty(propertyId, callerId);
        Lease current = leaseRepository
                .findLiveForProperty(property.getId(), NotraiusStatuses.LEASE_LIVE_STATUSES)
                .stream()
                .filter(lease -> lease.getValidity() != null)
                .max(Comparator.comparing(lease -> lease.getValidity().getFromDate(),
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
        return propertyMapper.toTenancyResponse(property, current);
    }

    /**
     * An unreadable row is <b>absent</b>, not forbidden: 404 says "no such
     * property here" and 403 would say "this property exists and is not yours" —
     * which is the whole id space of every owner's drafts, listed for free. The
     * owner (or a live delegate of theirs) still reads their own draft; anyone
     * else gets the same answer the row would get if it had never been created.
     * <p>
     * <b>Two rungs are public</b> (UPGRADE.md D2): {@code PUBLISHED}, because it
     * is on the market, and {@code LEASED}, because someone lives there. The
     * lease transaction must not lock its own tenant out of the home it just
     * rented, and a visitor must not lose the listing mid-visit when the flip
     * lands. What travels publicly for a LEASED row is the row itself — the
     * occupancy dates are their own dates-only projection
     * ({@code GET /{propertyId}/tenancy}), never tenant or financial detail.
     * <p>
     * {@code DRAFT}, {@code PAUSED} and {@code SUSPENDED} stay private (D3).
     * Staff read every rung: moderation cannot suspend a row it cannot read.
     */
    private void requireUnpublishedInvisible(Property property, UUID callerId) {
        String status = statusCodeOf(property);
        if (NotraiusStatuses.PROPERTY_PUBLISHED.equals(status)
                || NotraiusStatuses.PROPERTY_LEASED.equals(status)) {
            return;
        }
        if (SecurityAuthorityUtils.currentIsStaff()) {
            return;
        }
        try {
            requireOwnership("Only the property's owner may read it before it is published",
                    property, callerId);
        } catch (ForbiddenException e) {
            throw new NotFoundException(NotraiusErrors.PROPERTY_NOT_FOUND,
                    "Property not found: " + property.getId());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Amenity holdings
    // ═══════════════════════════════════════════════════════════════════════

    /** The property's amenities — visible with the property. */
    @Transactional(readOnly = true)
    public List<PropertyAmenityResponse> listAmenities(UUID propertyId, UUID callerId) {
        Property property = requireReadableProperty(propertyId, callerId);
        return amenityHoldingRepository.findByPropertyId(property.getId()).stream()
                .map(propertyMapper::toAmenityResponse)
                .toList();
    }

    /**
     * Select an amenity (or re-value one already held). The catalogue amenity
     * must be ACTIVE — a retired or disabled amenity is not a choice — and the
     * value must match its declared data type, because "24/7" is not a boolean
     * and a viewer will read it as what the type says it is.
     */
    @Transactional
    public PropertyAmenityResponse setAmenity(UUID propertyId, PropertyAmenityRequest request, UUID callerId) {
        Property property = requireOwnedProperty(propertyId, callerId);
        Amenity amenity = amenityRepository.findById(request.getAmenityId())
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.AMENITY_NOT_FOUND,
                        "Amenity not found: " + request.getAmenityId()));
        if (!NotraiusStatuses.AMENITY_ACTIVE.equals(statusCodeOf(amenity))) {
            throw new ConflictException(NotraiusErrors.PROPERTY_AMENITY_NOT_LIVE,
                    "Amenity '" + amenity.getCode() + "' is " + statusCodeOf(amenity)
                            + " and cannot be selected");
        }
        String value = validateAmenityValue(amenity, request.getValue());

        PropertyAmenity holding = amenityHoldingRepository
                .findByPropertyIdAndAmenityId(propertyId, amenity.getId())
                .orElse(null);
        boolean created = holding == null;
        if (created) {
            holding = new PropertyAmenity();
            holding.setProperty(property);
            holding.setAmenity(amenity);
            holding.setCreatedBy(callerId);
        }
        holding.setValue(value);
        holding.setStatus(statusResolver.resolve(Entities.PROPERTY_AMENITIES,
                NotraiusStatuses.PROPERTY_AMENITY_ACTIVE));
        amenityHoldingRepository.save(holding);

        PropertyAmenityResponse response = propertyMapper.toAmenityResponse(holding);
        audit(NotraiusActivities.Properties.AMENITY_SET, holding.getId(), created ? "SET" : "UPDATE",
                response, null);
        return response;
    }

    /** The owner's own switch: stop advertising an amenity without forgetting it. */
    @Transactional
    public PropertyAmenityResponse disableAmenity(UUID propertyId, UUID amenityId, UUID callerId) {
        Property property = requireOwnedProperty(propertyId, callerId);
        PropertyAmenity holding = requireHolding(property.getId(), amenityId);
        requireHoldingStatus(holding, NotraiusStatuses.PROPERTY_AMENITY_ACTIVE, "disable");

        PropertyAmenityResponse before = propertyMapper.toAmenityResponse(holding);
        holding.setStatus(statusResolver.resolve(Entities.PROPERTY_AMENITIES,
                NotraiusStatuses.PROPERTY_AMENITY_DISABLED));
        amenityHoldingRepository.save(holding);

        PropertyAmenityResponse after = propertyMapper.toAmenityResponse(holding);
        audit(NotraiusActivities.Properties.AMENITY_DISABLE, holding.getId(), "DISABLE", after, before);
        return after;
    }

    /**
     * Turn it back on. Refused while the catalogue amenity itself is off: the
     * owner cannot re-enable what the catalogue has switched off underneath them.
     */
    @Transactional
    public PropertyAmenityResponse enableAmenity(UUID propertyId, UUID amenityId, UUID callerId) {
        Property property = requireOwnedProperty(propertyId, callerId);
        PropertyAmenity holding = requireHolding(property.getId(), amenityId);
        requireHoldingStatus(holding, NotraiusStatuses.PROPERTY_AMENITY_DISABLED, "enable");
        if (!NotraiusStatuses.AMENITY_ACTIVE.equals(statusCodeOf(holding.getAmenity()))) {
            throw new ConflictException(NotraiusErrors.PROPERTY_AMENITY_NOT_LIVE,
                    "Amenity '" + holding.getAmenity().getCode() + "' is "
                            + statusCodeOf(holding.getAmenity()) + " in the catalogue");
        }

        PropertyAmenityResponse before = propertyMapper.toAmenityResponse(holding);
        holding.setStatus(statusResolver.resolve(Entities.PROPERTY_AMENITIES,
                NotraiusStatuses.PROPERTY_AMENITY_ACTIVE));
        amenityHoldingRepository.save(holding);

        PropertyAmenityResponse after = propertyMapper.toAmenityResponse(holding);
        audit(NotraiusActivities.Properties.AMENITY_ENABLE, holding.getId(), "ENABLE", after, before);
        return after;
    }

    /** Forget an amenity altogether. The holding row is the property's own note. */
    @Transactional
    public void deleteAmenity(UUID propertyId, UUID amenityId, UUID callerId) {
        Property property = requireOwnedProperty(propertyId, callerId);
        PropertyAmenity holding = requireHolding(property.getId(), amenityId);

        PropertyAmenityResponse before = propertyMapper.toAmenityResponse(holding);
        amenityHoldingRepository.delete(holding);
        audit(NotraiusActivities.Properties.AMENITY_DELETE, holding.getId(), "DELETE", null, before);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Forward hook — the catalogue's amenity cascade (config module)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Suppress every holding of a catalogue amenity that was just switched off.
     * Called by the config module's amenity lifecycle: a property does not lose
     * the amenity, it loses the right to show it, and re-enabling restores only
     * what the catalogue suppressed — never what the owner had switched off.
     */
    @Transactional
    public int suppressAmenityHoldings(UUID amenityId) {
        int suppressed = 0;
        int pageSize = Math.max(1, properties.getSweepPageSize());
        UUID afterId = SweepLedger.Cursor.NIL;
        while (true) {
            List<PropertyAmenity> page = amenityHoldingRepository.findByAmenityIdPage(amenityId, afterId,
                    PageRequest.of(0, pageSize));
            if (page.isEmpty()) {
                break;
            }
            for (PropertyAmenity holding : page) {
                if (!NotraiusStatuses.PROPERTY_AMENITY_ACTIVE.equals(statusCodeOf(holding))) {
                    continue;
                }
                PropertyAmenityResponse before = propertyMapper.toAmenityResponse(holding);
                holding.setStatus(statusResolver.resolve(Entities.PROPERTY_AMENITIES,
                        NotraiusStatuses.PROPERTY_AMENITY_SUPPRESSED));
                amenityHoldingRepository.save(holding);
                audit(NotraiusActivities.Properties.AMENITY_DISABLE, holding.getId(), "SUPPRESS",
                        propertyMapper.toAmenityResponse(holding), before);
                suppressed++;
            }
            afterId = page.get(page.size() - 1).getId();
            amenityHoldingRepository.flush();
            if (page.size() < pageSize) {
                break;
            }
        }
        return suppressed;
    }

    /** Restore exactly the catalogue-suppressed holdings of a re-enabled amenity. */
    @Transactional
    public int restoreAmenityHoldings(UUID amenityId) {
        int restored = 0;
        int pageSize = Math.max(1, properties.getSweepPageSize());
        UUID afterId = SweepLedger.Cursor.NIL;
        while (true) {
            List<PropertyAmenity> page = amenityHoldingRepository.findByAmenityIdPage(amenityId, afterId,
                    PageRequest.of(0, pageSize));
            if (page.isEmpty()) {
                break;
            }
            for (PropertyAmenity holding : page) {
                if (!NotraiusStatuses.PROPERTY_AMENITY_SUPPRESSED.equals(statusCodeOf(holding))) {
                    continue;
                }
                PropertyAmenityResponse before = propertyMapper.toAmenityResponse(holding);
                holding.setStatus(statusResolver.resolve(Entities.PROPERTY_AMENITIES,
                        NotraiusStatuses.PROPERTY_AMENITY_ACTIVE));
                amenityHoldingRepository.save(holding);
                audit(NotraiusActivities.Properties.AMENITY_ENABLE, holding.getId(), "RESTORE",
                        propertyMapper.toAmenityResponse(holding), before);
                restored++;
            }
            afterId = page.get(page.size() - 1).getId();
            amenityHoldingRepository.flush();
            if (page.size() < pageSize) {
                break;
            }
        }
        return restored;
    }

    /** The catalogue's delete gate: a held amenity cannot be removed underneath. */
    @Transactional(readOnly = true)
    public boolean isAmenityHeld(UUID amenityId) {
        return amenityHoldingRepository.existsByAmenityId(amenityId);
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
    // Gates and helpers
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * The completeness gate — what "published" promises: a kind, the anchors, a
     * price that answers now, and every mandatory standard valued.
     */
    private void requireComplete(Property property) {
        if (property.getType() == null) {
            throw new ConflictException(NotraiusErrors.PROPERTY_INCOMPLETE,
                    "Property " + property.getId() + " has no type");
        }
        if (property.getLocationId() == null || property.getStateId() == null
                || property.getCurrencyId() == null) {
            throw new ConflictException(NotraiusErrors.PROPERTY_LOCATION_REQUIRED,
                    "Property " + property.getId() + " is not anchored (location → state → currency)");
        }
        long answering = pricingRepository.countAnsweringNow(property.getId(),
                NotraiusStatuses.PRICING_LIVE, Instant.now());
        if (answering == 0) {
            throw new ConflictException(NotraiusErrors.PROPERTY_INCOMPLETE,
                    "Property " + property.getId() + " has no pricing answering now");
        }
        termsService.requireMandatoryValuesComplete(property);
    }

    /**
     * KYC gates creation and publication. The provider being unreachable is a
     * refusal, not a pass: the gate fails closed.
     */
    private void requireKyc(UUID callerId) {
        try {
            if (!userService.isKycVerified(callerId)) {
                throw new ForbiddenException(NotraiusErrors.PROPERTY_KYC_REQUIRED,
                        "A verified identity is required to list a property");
            }
        } catch (UserClient.KycUnavailableException e) {
            throw new ConflictException(NotraiusErrors.PROPERTY_KYC_UNAVAILABLE,
                    "The identity provider is unavailable, so the KYC gate cannot be passed");
        }
    }

    private Type requirePropertyType(UUID typeId) {
        Type type = typeRepository.findById(typeId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.PROPERTY_TYPE_NOT_FOUND,
                        "Type not found: " + typeId));
        String entityCode = type.getEntity() != null ? type.getEntity().getCode() : null;
        if (!Entities.PROPERTY.equals(entityCode)) {
            throw new BadRequestException(NotraiusErrors.PROPERTY_TYPE_ENTITY_MISMATCH,
                    "Type '" + type.getCode() + "' belongs to " + entityCode + ", not PROPERTY");
        }
        if (!NotraiusStatuses.TYPE_ACTIVE.equals(statusCodeOf(type))) {
            throw new ConflictException(NotraiusErrors.PROPERTY_TYPE_NOT_LIVE,
                    "Type '" + type.getCode() + "' is " + statusCodeOf(type));
        }
        return type;
    }

    private String validateAmenityValue(Amenity amenity, String rawValue) {
        String value = trimToNull(rawValue);
        if (value == null) {
            throw new BadRequestException(NotraiusErrors.PROPERTY_AMENITY_VALUE_INVALID,
                    "Amenity '" + amenity.getCode() + "' needs a value");
        }
        String dataType = amenity.getDataType() != null ? amenity.getDataType().name() : null;
        boolean valid = switch (dataType == null ? "" : dataType) {
            case "BOOLEAN" -> "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
            case "INTEGER" -> value.matches("\\d{1,9}");
            default -> value.length() <= 255;
        };
        if (!valid) {
            throw new BadRequestException(NotraiusErrors.PROPERTY_AMENITY_VALUE_INVALID,
                    "Value '" + value + "' is not a valid " + dataType + " for amenity '"
                            + amenity.getCode() + "'");
        }
        return value;
    }

    private PropertyAmenity requireHolding(UUID propertyId, UUID amenityId) {
        return amenityHoldingRepository.findByPropertyIdAndAmenityId(propertyId, amenityId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.PROPERTY_AMENITY_NOT_FOUND,
                        "Property " + propertyId + " does not hold amenity " + amenityId));
    }

    private void requireHoldingStatus(PropertyAmenity holding, String expected, String operation) {
        String actual = statusCodeOf(holding);
        if (!expected.equals(actual)) {
            throw new ConflictException(NotraiusErrors.PROPERTY_AMENITY_INVALID_STATE,
                    "Holding is " + actual + "; " + operation + " requires " + expected);
        }
    }

    /**
     * The owner's write gate. A suspended property is frozen: moderation's stop
     * cannot be edited around, which is the whole point of the state.
     */
    private Property requireOwnedProperty(UUID propertyId, UUID callerId) {
        Property property = requireProperty(propertyId);
        requireOwnership("Only the property's owner may change it", property, callerId);
        if (NotraiusStatuses.PROPERTY_SUSPENDED.equals(statusCodeOf(property))) {
            throw new ConflictException(NotraiusErrors.PROPERTY_INVALID_STATE,
                    "Property " + propertyId + " is suspended; only moderation can lift it");
        }
        return property;
    }

    private Property requireReadableProperty(UUID propertyId, UUID callerId) {
        Property property = requireProperty(propertyId);
        requireUnpublishedInvisible(property, callerId);
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

    private void requireStatusIn(Property property, String operation, String... allowed) {
        String actual = statusCodeOf(property);
        for (String candidate : allowed) {
            if (candidate.equals(actual)) {
                return;
            }
        }
        throw new ConflictException(NotraiusErrors.PROPERTY_INVALID_STATE,
                "Property is " + actual + "; " + operation + " requires one of "
                        + String.join(", ", allowed));
    }

    private boolean isPublished(Property property) {
        return NotraiusStatuses.PROPERTY_PUBLISHED.equals(statusCodeOf(property));
    }

    private String statusCodeOf(Property property) {
        return property.getStatus() != null ? property.getStatus().getCode() : null;
    }

    private String statusCodeOf(Type type) {
        return type.getStatus() != null ? type.getStatus().getCode() : null;
    }

    private String statusCodeOf(Amenity amenity) {
        return amenity.getStatus() != null ? amenity.getStatus().getCode() : null;
    }

    private String statusCodeOf(PropertyAmenity holding) {
        return holding.getStatus() != null ? holding.getStatus().getCode() : null;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * A property's history is a read model, not a shadow: {@code ?version=N} and
     * {@code /revisions} answer from the chain, so the entry is written through
     * {@link RequiredAuditWriter#writeRequired} and a failure aborts the write
     * that would otherwise move the row past a revision nobody recorded.
     */
    private void audit(String activityCode, UUID referenceId, String operation, Object after, Object before) {
        auditTrail.recordRequired(Entities.PROPERTY, activityCode, referenceId, operation, before, after);
    }
}
