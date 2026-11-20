package com.notraius.modules.core.config.reference;

import com.fasterxml.jackson.databind.JsonNode;
import com.notraius.common.audit.AuditTrail;
import com.notraius.common.NotraiusErrors;
import com.notraius.common.services.visit.QueryFilters;
import com.notraius.common.services.logs.StatusResolver;
import com.notraius.database.entities.lease.reference.amenity.Amenity;
import com.notraius.database.entities.lease.reference.amenity.AmenityDataType;
import com.notraius.database.entities.lease.reference.status.Duration;
import com.notraius.database.entities.lease.reference.amenity.Entities;
import com.notraius.database.entities.lease.reference.status.Status;
import com.notraius.database.entities.lease.reference.status.Type;
import com.notraius.database.repositories.asset.asset.PricingRepository;
import com.notraius.database.repositories.asset.asset.PropertyRepository;
import com.notraius.database.repositories.asset.config.PolicyRepository;
import com.notraius.database.repositories.lease.reference.duration.AmenityRepository;
import com.notraius.database.repositories.lease.reference.duration.DurationRepository;
import com.notraius.database.repositories.lease.reference.duration.EntitiesRepository;
import com.notraius.database.repositories.lease.reference.duration.TypeRepository;
import com.notraius.modules.lease.asset.property.PropertyService;
import com.notraius.modules.core.config.reference.ReferenceMapper;
import com.notraius.modules.core.config.reference.dto.request.amenity.AmenityCreateRequest;
import com.notraius.modules.core.config.reference.dto.request.amenity.AmenityUpdateRequest;
import com.notraius.modules.core.config.reference.dto.request.duration.DurationCreateRequest;
import com.notraius.modules.core.config.reference.dto.request.duration.DurationUpdateRequest;
import com.notraius.modules.core.config.reference.dto.request.type.TypeCreateRequest;
import com.notraius.modules.core.config.reference.dto.request.type.TypeReparentRequest;
import com.notraius.modules.core.config.reference.dto.request.type.TypeUpdateRequest;
import com.notraius.modules.core.config.reference.dto.response.AmenityResponse;
import com.notraius.modules.core.config.reference.dto.response.DurationResponse;
import com.notraius.modules.core.config.reference.dto.response.TypeResponse;
import com.notraius.modules.core.core.common.constants.NotraiusActivities;
import com.notraius.modules.core.core.common.constants.NotraiusStatuses;
import lombok.extern.slf4j.Slf4j;
import org.primarion.module.app.access.consent.consent.service.subscription.OwnershipGuard;
import org.primarion.module.communication.errors.client.BadRequestException;
import org.primarion.module.communication.errors.client.ConflictException;
import org.primarion.module.communication.errors.client.ForbiddenException;
import org.primarion.module.communication.errors.client.NotFoundException;
import org.primarion.module.utils.utils.security.security.SecurityAuthorityUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import com.notraius.modules.core.config.rules.RuleService;

/**
 * The config module's reference sub-module: the three catalogues every other
 * module selects from — <b>types</b> (PLAN.md Part 1), <b>durations</b>
 * (Part 2) and <b>amenities</b> (Part 3.3).
 * <p>
 * One service, one controller, per the operator's grouping: the three
 * catalogues share a governance shape (a status lifecycle, a code identity, an
 * in-use lock) and differ only in their laws, which are stated per method.
 * Their <i>authorities</i> differ, which is why each owns its own activity
 * family — a shared family would declare two policies on the same
 * {@code (activity, operation)} pair and fail the one-pair law at boot.
 * <p>
 * <b>The laws this class is responsible for.</b>
 * <ul>
 *   <li>Types: code immutable and unique inside its entity; the hierarchy
 *       never crosses an entity and never cycles; a type in use is retired,
 *       never deleted; a retired type is read-only history.</li>
 *   <li>Durations: (type, value) is the identity; the value is positive; a
 *       duration bound to a live pricing is retired, never edited or
 *       deleted.</li>
 *   <li>Amenities: any user proposes (PENDING), staff birth is live at once
 *       (ACTIVE); the proposer may withdraw only while PENDING; only staff
 *       manage a live row; code uniqueness holds among ACTIVE rows only; the
 *       data type is fixed at birth; disable is reversible, retire is
 *       checked against holdings.</li>
 * </ul>
 * <p>
 * <b>Every write is audited</b> with before/after data through the host
 * {@link AuditLogWriter}, because the catalogue's history is the only record
 * of what each admin saw and changed (Part 1's concurrency paragraph). The
 * audit call is fail-soft: a broken audit row must never roll back the
 * business transaction it observes.
 * <p>
 * <b>Why no {@code ActivityPolicyGuard} here.</b> That guard resolves the
 * caller's dynamic policy — plan limits and consent — for domain acts that
 * spend a quota or act for another account. Catalogue administration has no
 * dynamic policy: its whole authority is the static tier declared on the
 * endpoint, exactly as the contract module's type catalogue does it.
 */
@Service
@Slf4j
public class ReferenceService extends OwnershipGuard {

    /** Guard against a pre-existing cycle turning a walk into a spin. */
    private static final int MAX_HIERARCHY_DEPTH = 100;

    private final TypeRepository typeRepository;
    private final PolicyRepository policyRepository;
    private final DurationRepository durationRepository;
    private final AmenityRepository amenityRepository;
    /** The asset module's live references: a catalogue row in use must not vanish. */
    private final PropertyRepository propertyRepository;
    private final PricingRepository pricingRepository;
    private final PropertyService propertyService;
    private final EntitiesRepository entitiesRepository;
    private final StatusResolver statusResolver;
    private final ReferenceMapper referenceMapper;
    private final AuditTrail auditTrail;
    private final QueryFilters queryFilters;

    public ReferenceService(TypeRepository typeRepository,
                            PolicyRepository policyRepository,
                            DurationRepository durationRepository,
                            AmenityRepository amenityRepository,
                            PropertyRepository propertyRepository,
                            PricingRepository pricingRepository,
                            PropertyService propertyService,
                            EntitiesRepository entitiesRepository,
                            StatusResolver statusResolver,
                            ReferenceMapper referenceMapper,
                            AuditTrail auditTrail,
                            QueryFilters queryFilters) {
        this.typeRepository = typeRepository;
        this.policyRepository = policyRepository;
        this.durationRepository = durationRepository;
        this.amenityRepository = amenityRepository;
        this.propertyRepository = propertyRepository;
        this.pricingRepository = pricingRepository;
        this.propertyService = propertyService;
        this.entitiesRepository = entitiesRepository;
        this.statusResolver = statusResolver;
        this.referenceMapper = referenceMapper;
        this.auditTrail = auditTrail;
        this.queryFilters = queryFilters;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TYPES — Part 1
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Mint a type. The code is normalised to upper case before the duplicate
     * check, so {@code hour} and {@code HOUR} cannot become two rows of the
     * same kind.
     */
    @Transactional
    public TypeResponse createType(TypeCreateRequest request, UUID userId) {
        String entityCode = normalise(request.getEntityCode());
        Entities entity = entitiesRepository.findByCode(entityCode)
                .orElseThrow(() -> new BadRequestException(NotraiusErrors.TYPE_ENTITY_NOT_FOUND,
                        "Entity not found: " + entityCode));

        String code = normalise(request.getCode());
        if (typeRepository.existsByEntityIdAndCode(entity.getId(), code)) {
            throw new ConflictException(NotraiusErrors.TYPE_CODE_DUPLICATE,
                    "A type with code '" + code + "' already exists for entity " + entityCode);
        }

        Type type = new Type();
        type.setEntity(entity);
        type.setParent(resolveParent(request.getParentId(), entity));
        type.setCode(code);
        type.setLabel(request.getLabel().trim());
        type.setDescription(trimToNull(request.getDescription()));
        type.setStatus(statusResolver.resolve(entityCode, NotraiusStatuses.TYPE_ACTIVE));
        type.setCreatedBy(userId);

        typeRepository.save(type);
        log.info("Type created: id={} entity={} code={}", type.getId(), entityCode, code);
        audit(Entities.TYPES, NotraiusActivities.Types.CREATE, type.getId(), "CREATE", null,
                referenceMapper.toResponse(type));
        return referenceMapper.toResponse(type);
    }

    /**
     * The catalogue page. By default only ACTIVE rows (what a selection UI
     * must offer); {@code activeOnly=false} returns history too, for an
     * admin's catalogue review.
     */
    @Transactional(readOnly = true)
    public Page<TypeResponse> listTypes(String entityCode, UUID parentId, Boolean activeOnly,
                                        String search, Pageable pageable) {
        String statusCode = Boolean.FALSE.equals(activeOnly) ? null : NotraiusStatuses.TYPE_ACTIVE;
        return typeRepository.search(queryFilters.entity(entityCode), parentId, statusCode,
                        queryFilters.search(search), pageable)
                .map(referenceMapper::toResponse);
    }

    /**
     * Read by id, whatever the status: a retired type must still resolve for
     * every row that already points at it.
     */
    @Transactional(readOnly = true)
    public TypeResponse getType(UUID typeId) {
        return referenceMapper.toResponse(requireType(typeId));
    }

    /**
     * Edit the human-facing fields. A retired type is read-only — editing it
     * would rewrite the meaning of history — and the code is never touched:
     * a different code is a different type (retire and recreate).
     */
    @Transactional
    public TypeResponse updateType(UUID typeId, TypeUpdateRequest request) {
        Type type = requireType(typeId);
        if (isRetired(type.getStatus())) {
            throw new ConflictException(NotraiusErrors.TYPE_RETIRED,
                    "Type " + type.getCode() + " is retired and cannot be edited");
        }

        TypeResponse before = referenceMapper.toResponse(type);
        if (request.getLabel() != null) {
            type.setLabel(request.getLabel().trim());
        }
        if (request.getDescription() != null) {
            type.setDescription(trimToNull(request.getDescription()));
        }
        typeRepository.save(type);

        TypeResponse after = referenceMapper.toResponse(type);
        audit(Entities.TYPES, NotraiusActivities.Types.UPDATE, typeId, "UPDATE", before, after);
        return after;
    }

    /**
     * Move the node. Three refusals: an unknown parent, a parent from another
     * entity (the entity is the namespace, so a property kind can never hang
     * beneath a visit format), and any move that would place the type beneath
     * itself or one of its own descendants — a cycle makes the catalogue
     * unresolvable.
     */
    @Transactional
    public TypeResponse reparentType(UUID typeId, TypeReparentRequest request) {
        Type type = requireType(typeId);
        UUID parentId = request.getParentId();

        Type parent = null;
        if (parentId != null) {
            parent = typeRepository.findById(parentId)
                    .orElseThrow(() -> new BadRequestException(NotraiusErrors.TYPE_PARENT_NOT_FOUND,
                            "Parent type not found: " + parentId));
            if (!parent.getEntity().getId().equals(type.getEntity().getId())) {
                throw new BadRequestException(NotraiusErrors.TYPE_PARENT_ENTITY_MISMATCH,
                        "Parent type belongs to entity " + parent.getEntity().getCode()
                                + " but this type belongs to " + type.getEntity().getCode());
            }
            requireLiveParent(parent);
            if (isSelfOrDescendant(parent, type.getId())) {
                throw new ConflictException(NotraiusErrors.TYPE_PARENT_CYCLE,
                        "Cannot place " + type.getCode() + " beneath " + parent.getCode()
                                + ": the parent is the type itself or one of its descendants");
            }
        }

        TypeResponse before = referenceMapper.toResponse(type);
        type.setParent(parent);
        typeRepository.save(type);

        TypeResponse after = referenceMapper.toResponse(type);
        audit(Entities.TYPES, NotraiusActivities.Types.REPARENT, typeId, "REPARENT", before, after);
        return after;
    }

    /**
     * Retire a type: it stops appearing in selection lists while every
     * existing reference keeps resolving. Idempotent — repeating a retire
     * changes nothing. Children are deliberately untouched (Part 1: retirement
     * does not cascade; a retired parent simply disappears from root-level
     * selection).
     */
    @Transactional
    public TypeResponse retireType(UUID typeId) {
        Type type = requireType(typeId);
        if (isRetired(type.getStatus())) {
            return referenceMapper.toResponse(type);
        }

        TypeResponse before = referenceMapper.toResponse(type);
        type.setStatus(statusResolver.resolve(Entities.TYPES, NotraiusStatuses.TYPE_RETIRED));
        typeRepository.save(type);

        TypeResponse after = referenceMapper.toResponse(type);
        audit(Entities.TYPES, NotraiusActivities.Types.RETIRE, typeId, "RETIRE", before, after);
        log.info("Type retired: id={} code={}", typeId, type.getCode());
        return after;
    }

    /**
     * Delete a type that nothing points at. The service answers the "is
     * anything pointing at this" question first, because a catalogue delete
     * that orphans a reference is a corrupt row (Part 1).
     * <p>
     * The asset module's references join this gate as equals:
     * {@code property.properties.type_id} and {@code property.pricings.type_id},
     * beside the in-catalogue {@code notraius.policies.type_id}. A kind that
     * classifies a property or prices one is in use, and in use means no delete.
     */
    @Transactional
    public void deleteType(UUID typeId) {
        Type type = requireType(typeId);

        if (typeRepository.existsByParentId(typeId)) {
            throw new ConflictException(NotraiusErrors.TYPE_IN_USE,
                    "Type " + type.getCode() + " has child types; retire it instead");
        }
        if (durationRepository.existsByTypeId(typeId)) {
            throw new ConflictException(NotraiusErrors.TYPE_IN_USE,
                    "Type " + type.getCode() + " is used by durations; retire it instead");
        }
        if (amenityRepository.existsByTypeId(typeId)) {
            throw new ConflictException(NotraiusErrors.TYPE_IN_USE,
                    "Type " + type.getCode() + " is used by amenities; retire it instead");
        }
        if (policyRepository.existsByTypeId(typeId)) {
            throw new ConflictException(NotraiusErrors.TYPE_IN_USE,
                    "Type " + type.getCode() + " is used by policies; retire it instead");
        }
        if (propertyRepository.existsByTypeId(typeId)) {
            throw new ConflictException(NotraiusErrors.TYPE_IN_USE,
                    "Type " + type.getCode() + " classifies properties; retire it instead");
        }
        if (pricingRepository.existsByTypeId(typeId)) {
            throw new ConflictException(NotraiusErrors.TYPE_IN_USE,
                    "Type " + type.getCode() + " prices properties; retire it instead");
        }

        TypeResponse before = referenceMapper.toResponse(type);
        typeRepository.delete(type);
        audit(Entities.TYPES, NotraiusActivities.Types.DELETE, typeId, "DELETE", before, null);
        log.info("Type deleted: id={} code={}", typeId, type.getCode());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DURATIONS — Part 2
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Mint a duration. Users do this themselves, so a non-canonical but
     * legitimate combination does not queue behind an admin; the two
     * disciplines that keep a user-grown catalogue clean are the identity
     * (type, value) and the positive value.
     */
    @Transactional
    public DurationResponse createDuration(DurationCreateRequest request, UUID userId) {
        Type type = requireLiveType(request.getTypeId(),
                NotraiusErrors.DURATION_TYPE_NOT_FOUND, NotraiusErrors.DURATION_TYPE_NOT_LIVE);

        Integer value = request.getValue();
        requirePositiveDurationValue(value);

        if (durationRepository.existsByTypeIdAndValue(type.getId(), value)) {
            throw new ConflictException(NotraiusErrors.DURATION_DUPLICATE,
                    type.getCode() + " + " + value + " already exists");
        }

        Duration duration = new Duration();
        duration.setType(type);
        duration.setValue(value);
        duration.setStatus(statusResolver.resolve(Entities.DURATIONS, NotraiusStatuses.DURATION_ACTIVE));
        duration.setCreatedBy(userId);

        durationRepository.save(duration);
        log.info("Duration created: id={} {}+{}", duration.getId(), type.getCode(), value);
        audit(Entities.DURATIONS, NotraiusActivities.Durations.CREATE, duration.getId(), "CREATE", null,
                referenceMapper.toResponse(duration));
        return referenceMapper.toResponse(duration);
    }

    /** Durations, optionally for one unit type; ACTIVE only by default. */
    @Transactional(readOnly = true)
    public Page<DurationResponse> listDurations(UUID typeId, Boolean activeOnly, Pageable pageable) {
        boolean liveOnly = !Boolean.FALSE.equals(activeOnly);
        Page<Duration> page;
        if (liveOnly) {
            UUID statusId = statusId(Entities.DURATIONS, NotraiusStatuses.DURATION_ACTIVE);
            page = typeId != null
                    ? durationRepository.findByTypeIdAndStatusId(typeId, statusId, pageable)
                    : durationRepository.findByStatusId(statusId, pageable);
        } else {
            page = typeId != null
                    ? durationRepository.findByTypeId(typeId, pageable)
                    : durationRepository.findAll(pageable);
        }
        return page.map(referenceMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public DurationResponse getDuration(UUID durationId) {
        return referenceMapper.toResponse(requireDuration(durationId));
    }

    /**
     * Correct the value of a duration that is not in use.
     * <p>
     * Part 2 forbids editing a duration bound to a live pricing — that would
     * silently change the physical meaning of every pricing that references
     * it — and the honest answer is a new row plus a deliberate migration of
     * each pricing, visible as a pricing version in the logs.
     * <p>
     * A duration a pricing was born with cannot be edited: the pricing's unit
     * would change underneath an existing commitment, so the honest path is a
     * new duration plus a deliberate, versioned re-binding of each pricing.
     */
    @Transactional
    public DurationResponse updateDuration(UUID durationId, DurationUpdateRequest request) {
        Duration duration = requireDuration(durationId);
        if (isRetired(duration.getStatus())) {
            throw new ConflictException(NotraiusErrors.DURATION_RETIRED,
                    "Duration " + durationId + " is retired and cannot be edited");
        }

        Integer value = request.getValue();
        requirePositiveDurationValue(value);
        if (value.equals(duration.getValue())) {
            return referenceMapper.toResponse(duration);
        }
        if (durationRepository.existsByTypeIdAndValueAndIdNot(duration.getType().getId(), value, durationId)) {
            throw new ConflictException(NotraiusErrors.DURATION_DUPLICATE,
                    duration.getType().getCode() + " + " + value + " already exists");
        }
        // A bound duration's value IS the unit its pricings are counted in.
        if (pricingRepository.existsByDurationId(durationId)) {
            throw new ConflictException(NotraiusErrors.DURATION_IN_USE,
                    "Duration " + durationId + " gives a pricing its body; create a new one instead");
        }

        DurationResponse before = referenceMapper.toResponse(duration);
        duration.setValue(value);
        durationRepository.save(duration);

        DurationResponse after = referenceMapper.toResponse(duration);
        audit(Entities.DURATIONS, NotraiusActivities.Durations.UPDATE, durationId, "UPDATE", before, after);
        return after;
    }

    /** Retire a duration; idempotent, and every existing reference keeps resolving. */
    @Transactional
    public DurationResponse retireDuration(UUID durationId) {
        Duration duration = requireDuration(durationId);
        if (isRetired(duration.getStatus())) {
            return referenceMapper.toResponse(duration);
        }

        DurationResponse before = referenceMapper.toResponse(duration);
        duration.setStatus(statusResolver.resolve(Entities.DURATIONS, NotraiusStatuses.DURATION_RETIRED));
        durationRepository.save(duration);

        DurationResponse after = referenceMapper.toResponse(duration);
        audit(Entities.DURATIONS, NotraiusActivities.Durations.RETIRE, durationId, "RETIRE", before, after);
        return after;
    }

    /**
     * Delete an unused duration.
     * <p>
     * A duration a pricing holds is refused, exactly like a type in use,
     * because the reference must keep resolving.
     */
    @Transactional
    public void deleteDuration(UUID durationId) {
        Duration duration = requireDuration(durationId);
        if (pricingRepository.existsByDurationId(durationId)) {
            throw new ConflictException(NotraiusErrors.DURATION_IN_USE,
                    "Duration " + durationId + " gives a pricing its body; retire it instead");
        }

        DurationResponse before = referenceMapper.toResponse(duration);
        durationRepository.delete(duration);
        audit(Entities.DURATIONS, NotraiusActivities.Durations.DELETE, durationId, "DELETE", before, null);
        log.info("Duration deleted: id={}", durationId);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AMENITIES — Part 3.3
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Submit an amenity.
     * <p>
     * A personal or enterprise caller's submission lands in PENDING, attributed
     * to them so they may withdraw it; a staff caller's own submission is ACTIVE
     * at birth — staff birth is staff approval and needs no round trip. The
     * staff branch also enforces the live catalogue's uniqueness immediately,
     * because an ACTIVE row is live the instant it exists.
     * <p>
     * Code uniqueness is deliberately NOT checked for a proposal: two users may
     * propose "Pool" in parallel and both proposals are legitimate. The
     * collision is settled when one of them is confirmed.
     */
    @Transactional
    public AmenityResponse createAmenity(AmenityCreateRequest request, UUID userId) {
        Type type = requireLiveType(request.getTypeId(),
                NotraiusErrors.AMENITY_TYPE_NOT_FOUND, NotraiusErrors.AMENITY_TYPE_NOT_LIVE);

        AmenityDataType dataType = AmenityDataType.from(request.getDataType());
        if (dataType == null) {
            throw new BadRequestException(NotraiusErrors.AMENITY_DATA_TYPE_INVALID,
                    "Unknown amenity data type: " + request.getDataType());
        }

        String code = normalise(request.getCode());
        boolean staff = SecurityAuthorityUtils.currentIsStaff();
        if (staff) {
            requireNoLiveCodeCollision(code, null);
        }

        Amenity amenity = new Amenity();
        amenity.setType(type);
        amenity.setDataType(dataType);
        amenity.setCode(code);
        amenity.setLabel(request.getLabel().trim());
        amenity.setDescription(trimToNull(request.getDescription()));
        amenity.setCreatedBy(userId);
        amenity.setProposer(staff ? null : userId);
        amenity.setStatus(statusResolver.resolve(Entities.AMENITIES,
                staff ? NotraiusStatuses.AMENITY_ACTIVE : NotraiusStatuses.AMENITY_PENDING));

        amenityRepository.save(amenity);
        log.info("Amenity {}: id={} code={} by={}",
                staff ? "created live" : "proposed", amenity.getId(), code, userId);
        audit(Entities.AMENITIES, NotraiusActivities.Amenities.PROPOSE, amenity.getId(), "PROPOSE", null,
                referenceMapper.toResponse(amenity));
        return referenceMapper.toResponse(amenity);
    }

    /**
     * The live catalogue a property selects from — ACTIVE rows only. With
     * {@code mine=true} it answers the proposer's own submissions instead,
     * whatever their status, so a user can find their pending row again.
     */
    @Transactional(readOnly = true)
    public Page<AmenityResponse> listAmenities(UUID typeId, String search, boolean mine,
                                               UUID callerId, Pageable pageable) {
        String term = queryFilters.search(search);
        if (mine) {
            return amenityRepository.findMine(callerId, null, typeId, term, pageable)
                    .map(referenceMapper::toResponse);
        }
        return amenityRepository.findLive(statusId(Entities.AMENITIES, NotraiusStatuses.AMENITY_ACTIVE),
                        typeId, term, pageable)
                .map(referenceMapper::toResponse);
    }

    /** The moderation queue: every row that is not live, any status (staff only). */
    @Transactional(readOnly = true)
    public Page<AmenityResponse> listAmenitySubmissions(UUID typeId, String statusCode,
                                                        String search, Pageable pageable) {
        return amenityRepository.findSubmissions(NotraiusStatuses.AMENITY_ACTIVE,
                        queryFilters.status(Entities.AMENITIES, statusCode), typeId,
                        queryFilters.search(search), pageable)
                .map(referenceMapper::toResponse);
    }

    /**
     * Read one amenity by id.
     * <p>
     * <b>Only the live row is everyone's.</b> The catalogue answers for
     * ACTIVE; a row that never went live (PENDING, CANCELLED, REJECTED) or
     * that staff have taken back out (DISABLED, RETIRED) is the moderation
     * queue's surface, so it resolves only for its proposer — through the
     * estate's ownership guard, delegation included — or for staff. Anyone
     * else gets <b>not found</b>, never forbidden: a 403 would confirm the
     * row exists, which is exactly the leak the ADMIN-only submission queue
     * forbids. This mirrors {@code PropertyService.requireUnpublishedInvisible}
     * and {@code RuleService.getRule} (a PRIVATE rule answers 404).
     */
    @Transactional(readOnly = true)
    public AmenityResponse getAmenity(UUID amenityId, UUID callerId) {
        Amenity amenity = requireAmenity(amenityId);
        requireReadableAmenity(amenity, callerId);
        return referenceMapper.toResponse(amenity);
    }

    /** The read gate for one amenity — live = public, anything else = proposer or staff. */
    private void requireReadableAmenity(Amenity amenity, UUID callerId) {
        if (NotraiusStatuses.AMENITY_ACTIVE.equals(statusCodeOf(amenity))) {
            return;
        }
        if (SecurityAuthorityUtils.currentIsStaff()) {
            return;
        }
        try {
            requireOwnership("Only the amenity's proposer may read it before it is live",
                    amenity, callerId);
        } catch (ForbiddenException e) {
            throw new NotFoundException(NotraiusErrors.AMENITY_NOT_FOUND,
                    "Amenity not found: " + amenity.getId());
        }
    }

    /**
     * Edit a live amenity's human-facing fields (staff only).
     * <p>
     * Only an ACTIVE or DISABLED row: those are the two states in which the
     * row is part of the platform's catalogue. A proposal still belongs to its
     * proposer and a retired row is history.
     */
    @Transactional
    public AmenityResponse updateAmenity(UUID amenityId, AmenityUpdateRequest request) {
        Amenity amenity = requireAmenity(amenityId);
        requireStatusIn(amenity, "edit", NotraiusStatuses.AMENITY_ACTIVE, NotraiusStatuses.AMENITY_DISABLED);

        AmenityResponse before = referenceMapper.toResponse(amenity);
        if (request.getLabel() != null) {
            amenity.setLabel(request.getLabel().trim());
        }
        if (request.getDescription() != null) {
            amenity.setDescription(trimToNull(request.getDescription()));
        }
        amenityRepository.save(amenity);

        AmenityResponse after = referenceMapper.toResponse(amenity);
        audit(Entities.AMENITIES, NotraiusActivities.Amenities.UPDATE, amenityId, "UPDATE", before, after);
        return after;
    }

    /**
     * Withdraw a pending proposal — the proposer's own act, and only while
     * the admin has not decided.
     * <p>
     * Cancellation races the admin's decision; both take the row's FOR UPDATE
     * lock and both require PENDING, so the first writer wins and the second
     * finds a decided row and fails cleanly. A cancelled proposal can never
     * later be confirmed.
     * <p>
     * The ownership guard is the estate's (proposer, or a live delegation
     * acting for them), so staff cannot withdraw someone else's proposal —
     * staff REJECT it instead.
     */
    @Transactional
    public AmenityResponse cancelAmenity(UUID amenityId, UUID callerId) {
        Amenity amenity = amenityRepository.findByIdForUpdate(amenityId)
                .orElseThrow(() -> amenityNotFound(amenityId));
        requireOwnership("Only the proposer may withdraw this amenity proposal", amenity, callerId);
        requireStatus(amenity, NotraiusStatuses.AMENITY_PENDING, "withdraw");

        AmenityResponse before = referenceMapper.toResponse(amenity);
        amenity.setStatus(statusResolver.resolve(Entities.AMENITIES, NotraiusStatuses.AMENITY_CANCELLED));
        amenityRepository.save(amenity);

        AmenityResponse after = referenceMapper.toResponse(amenity);
        audit(Entities.AMENITIES, NotraiusActivities.Amenities.CANCEL, amenityId, "CANCEL", before, after);
        return after;
    }

    /**
     * Confirm a pending proposal. Refused when another ACTIVE amenity already
     * carries the code — the duplicate is settled by rejecting this proposal,
     * not by widening the catalogue with two identical entries.
     */
    @Transactional
    public AmenityResponse confirmAmenity(UUID amenityId) {
        Amenity amenity = amenityRepository.findByIdForUpdate(amenityId)
                .orElseThrow(() -> amenityNotFound(amenityId));
        requireStatus(amenity, NotraiusStatuses.AMENITY_PENDING, "confirm");
        requireNoLiveCodeCollision(amenity.getCode(), amenityId);

        AmenityResponse before = referenceMapper.toResponse(amenity);
        amenity.setStatus(statusResolver.resolve(Entities.AMENITIES, NotraiusStatuses.AMENITY_ACTIVE));
        amenityRepository.save(amenity);

        AmenityResponse after = referenceMapper.toResponse(amenity);
        audit(Entities.AMENITIES, NotraiusActivities.Amenities.CONFIRM, amenityId, "CONFIRM", before, after);
        log.info("Amenity confirmed: id={} code={}", amenityId, amenity.getCode());
        return after;
    }

    /** Reject a pending proposal. The idea is not blacklisted — only this row ends. */
    @Transactional
    public AmenityResponse rejectAmenity(UUID amenityId) {
        Amenity amenity = amenityRepository.findByIdForUpdate(amenityId)
                .orElseThrow(() -> amenityNotFound(amenityId));
        requireStatus(amenity, NotraiusStatuses.AMENITY_PENDING, "reject");

        AmenityResponse before = referenceMapper.toResponse(amenity);
        amenity.setStatus(statusResolver.resolve(Entities.AMENITIES, NotraiusStatuses.AMENITY_REJECTED));
        amenityRepository.save(amenity);

        AmenityResponse after = referenceMapper.toResponse(amenity);
        audit(Entities.AMENITIES, NotraiusActivities.Amenities.REJECT, amenityId, "REJECT", before, after);
        return after;
    }

    /**
     * Switch a live amenity off. Reversible, and never a local act: the
     * cascade takes every property holding of this amenity down in the same
     * transaction while the property keeps its own status.
     * <p>
     * The cascade marks each holding it suppresses as catalogue-caused
     * ({@code SUPPRESSED}, never the owner's own {@code DISABLED}) so that
     * {@link #enableAmenity} restores exactly those and leaves the owner's own
     * choices untouched — the owner's will survives the platform's switch in
     * both directions. Stored holding values are kept, never erased.
     */
    @Transactional
    public AmenityResponse disableAmenity(UUID amenityId) {
        Amenity amenity = requireAmenityForUpdate(amenityId);
        requireStatus(amenity, NotraiusStatuses.AMENITY_ACTIVE, "disable");

        AmenityResponse before = referenceMapper.toResponse(amenity);
        amenity.setStatus(statusResolver.resolve(Entities.AMENITIES, NotraiusStatuses.AMENITY_DISABLED));
        amenityRepository.save(amenity);

        int suppressed = propertyService.suppressAmenityHoldings(amenityId);

        AmenityResponse after = referenceMapper.toResponse(amenity);
        audit(Entities.AMENITIES, NotraiusActivities.Amenities.DISABLE, amenityId, "DISABLE", before, after);
        log.info("Amenity disabled: id={} code={} suppressedHoldings={}",
                amenityId, amenity.getCode(), suppressed);
        return after;
    }

    /**
     * Switch a disabled amenity back on. The mirror of disable: it restores
     * only the holdings the catalogue suppressed, never the ones an owner
     * disabled deliberately (see {@link #disableAmenity}).
     * <p>
     * Re-enabling is also a catalogue act, so the live-code uniqueness is
     * re-checked: while this amenity was off, another row may have taken its
     * code, and resurrecting it would put two ACTIVE rows under one code.
     */
    @Transactional
    public AmenityResponse enableAmenity(UUID amenityId) {
        Amenity amenity = requireAmenityForUpdate(amenityId);
        requireStatus(amenity, NotraiusStatuses.AMENITY_DISABLED, "enable");
        requireNoLiveCodeCollision(amenity.getCode(), amenityId);

        AmenityResponse before = referenceMapper.toResponse(amenity);
        amenity.setStatus(statusResolver.resolve(Entities.AMENITIES, NotraiusStatuses.AMENITY_ACTIVE));
        amenityRepository.save(amenity);

        int restored = propertyService.restoreAmenityHoldings(amenityId);

        AmenityResponse after = referenceMapper.toResponse(amenity);
        audit(Entities.AMENITIES, NotraiusActivities.Amenities.ENABLE, amenityId, "ENABLE", before, after);
        log.info("Amenity enabled: id={} code={} restoredHoldings={}",
                amenityId, amenity.getCode(), restored);
        return after;
    }

    /**
     * Remove an amenity from the catalogue for good. The rare, checked
     * operation: retirement is refused while a holding still references the
     * amenity (the plan prefers disable, which is reversible).
     */
    @Transactional
    public AmenityResponse retireAmenity(UUID amenityId) {
        Amenity amenity = requireAmenityForUpdate(amenityId);
        if (isRetired(amenity.getStatus())) {
            return referenceMapper.toResponse(amenity);
        }
        requireStatusIn(amenity, "retire", NotraiusStatuses.AMENITY_ACTIVE, NotraiusStatuses.AMENITY_DISABLED);
        if (propertyService.isAmenityHeld(amenityId)) {
            throw new ConflictException(NotraiusErrors.AMENITY_IN_USE,
                    "Amenity " + amenity.getCode() + " is held by a property; disable it instead");
        }

        AmenityResponse before = referenceMapper.toResponse(amenity);
        amenity.setStatus(statusResolver.resolve(Entities.AMENITIES, NotraiusStatuses.AMENITY_RETIRED));
        amenityRepository.save(amenity);

        AmenityResponse after = referenceMapper.toResponse(amenity);
        audit(Entities.AMENITIES, NotraiusActivities.Amenities.RETIRE, amenityId, "RETIRE", before, after);
        return after;
    }

    /**
     * Delete an amenity nothing holds — {@code property.amenities.amenity_id}
     * is checked first, so no holding is ever orphaned.
     */
    @Transactional
    public void deleteAmenity(UUID amenityId) {
        Amenity amenity = requireAmenityForUpdate(amenityId);
        if (propertyService.isAmenityHeld(amenityId)) {
            throw new ConflictException(NotraiusErrors.AMENITY_IN_USE,
                    "Amenity " + amenity.getCode() + " is held by a property and cannot be deleted");
        }

        AmenityResponse before = referenceMapper.toResponse(amenity);
        amenityRepository.delete(amenity);
        audit(Entities.AMENITIES, NotraiusActivities.Amenities.DELETE, amenityId, "DELETE", before, null);
        log.info("Amenity deleted: id={} code={}", amenityId, amenity.getCode());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // OwnershipGuard — "is this caller the proposer?"
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * args[0]: the amenity, args[1]: the caller id — the only ownership
     * question this sub-module asks. Catalogue rows have no owner; a pending
     * proposal does, and it is its proposer alone.
     */
    @Override
    public boolean canAccess(Object... args) {
        if (args.length < 2) {
            return false;
        }
        if (!(args[0] instanceof Amenity amenity) || !(args[1] instanceof UUID callerId)) {
            return false;
        }
        return callerId != null && callerId.equals(amenity.getProposer());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Lookups and guards
    // ═══════════════════════════════════════════════════════════════════════

    private Type requireType(UUID typeId) {
        return typeRepository.findById(typeId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.TYPE_NOT_FOUND,
                        "Type not found: " + typeId));
    }

    private Duration requireDuration(UUID durationId) {
        return durationRepository.findById(durationId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.DURATION_NOT_FOUND,
                        "Duration not found: " + durationId));
    }

    private Amenity requireAmenity(UUID amenityId) {
        return amenityRepository.findById(amenityId).orElseThrow(() -> amenityNotFound(amenityId));
    }

    private Amenity requireAmenityForUpdate(UUID amenityId) {
        return amenityRepository.findByIdForUpdate(amenityId).orElseThrow(() -> amenityNotFound(amenityId));
    }

    private NotFoundException amenityNotFound(UUID amenityId) {
        return new NotFoundException(NotraiusErrors.AMENITY_NOT_FOUND, "Amenity not found: " + amenityId);
    }

    /** A type a catalogue row may bind to: it must exist and be ACTIVE. */
    private Type requireLiveType(UUID typeId, NotraiusErrors notFound, NotraiusErrors notLive) {
        Type type = typeRepository.findById(typeId)
                .orElseThrow(() -> new BadRequestException(notFound, "Type not found: " + typeId));
        if (!NotraiusStatuses.TYPE_ACTIVE.equals(statusCodeOf(type))) {
            throw new BadRequestException(notLive,
                    "Type " + type.getCode() + " is " + statusCodeOf(type) + "; only an ACTIVE type may be bound");
        }
        return type;
    }

    /**
     * The parent of a new type: it must exist and share the child's entity,
     * because the entity binding is the namespace and the hierarchy never
     * leaves it (Part 1, "The type of a type").
     */
    private Type resolveParent(UUID parentId, Entities entity) {
        if (parentId == null) {
            return null;
        }
        Type parent = typeRepository.findById(parentId)
                .orElseThrow(() -> new BadRequestException(NotraiusErrors.TYPE_PARENT_NOT_FOUND,
                        "Parent type not found: " + parentId));
        if (!parent.getEntity().getId().equals(entity.getId())) {
            throw new BadRequestException(NotraiusErrors.TYPE_PARENT_ENTITY_MISMATCH,
                    "Parent type belongs to entity " + parent.getEntity().getCode()
                            + " but this type belongs to " + entity.getCode());
        }
        requireLiveParent(parent);
        return parent;
    }

    /**
     * A retired type is out of the catalogue, so nothing new may hang beneath
     * it: its existing children keep their own status (retirement does not
     * cascade), but the subtree stops growing.
     */
    private void requireLiveParent(Type parent) {
        if (isRetired(parent.getStatus())) {
            throw new ConflictException(NotraiusErrors.TYPE_RETIRED,
                    "Type " + parent.getCode() + " is retired; a type cannot be placed beneath it");
        }
    }

    /**
     * True when {@code candidate} is {@code ancestorId} itself or sits beneath
     * it. A pre-existing cycle in the data also answers true, which refuses
     * the move rather than blessing it — and the depth cap keeps the walk
     * finite whatever the data says.
     */
    private boolean isSelfOrDescendant(Type candidate, UUID ancestorId) {
        Set<UUID> seen = new HashSet<>();
        Type cursor = candidate;
        int depth = 0;
        while (cursor != null) {
            if (!seen.add(cursor.getId()) || depth++ > MAX_HIERARCHY_DEPTH) {
                log.warn("Cycle or over-deep hierarchy detected at type {}; refusing the move", cursor.getId());
                return true;
            }
            if (cursor.getId().equals(ancestorId)) {
                return true;
            }
            cursor = cursor.getParent();
        }
        return false;
    }

    /**
     * The live catalogue's code uniqueness. Staff birth, confirmation and
     * re-enabling all pass through here; proposals deliberately do not, and
     * the case-insensitive match is what makes a normalised code a real
     * identity rather than a spelling.
     */
    private void requireNoLiveCodeCollision(String code, UUID selfId) {
        UUID liveStatusId = statusId(Entities.AMENITIES, NotraiusStatuses.AMENITY_ACTIVE);
        if (amenityRepository.countLiveByCode(code, liveStatusId, selfId) > 0) {
            throw new ConflictException(NotraiusErrors.AMENITY_CODE_DUPLICATE,
                    "An active amenity with code '" + code + "' already exists");
        }
    }

    private void requireStatus(Amenity amenity, String expected, String operation) {
        String actual = statusCodeOf(amenity);
        if (!expected.equals(actual)) {
            throw new ConflictException(NotraiusErrors.AMENITY_INVALID_STATE,
                    "Amenity " + amenity.getCode() + " is " + actual + "; " + operation + " requires " + expected);
        }
    }

    private void requireStatusIn(Amenity amenity, String operation, String... allowed) {
        String actual = statusCodeOf(amenity);
        for (String candidate : allowed) {
            if (candidate.equals(actual)) {
                return;
            }
        }
        throw new ConflictException(NotraiusErrors.AMENITY_INVALID_STATE,
                "Amenity " + amenity.getCode() + " is " + actual + "; " + operation
                        + " requires one of " + String.join(", ", allowed));
    }

    private void requirePositiveDurationValue(Integer value) {
        if (value == null || value < 1) {
            throw new BadRequestException(NotraiusErrors.DURATION_VALUE_INVALID,
                    "A duration's value must be at least 1 (received: " + value + ")");
        }
    }

    private UUID statusId(String entityCode, String statusCode) {
        return statusResolver.resolve(entityCode, statusCode).getId();
    }

    private String statusCodeOf(Type type) {
        return type.getStatus() != null ? type.getStatus().getCode() : null;
    }

    private String statusCodeOf(Amenity amenity) {
        return amenity.getStatus() != null ? amenity.getStatus().getCode() : null;
    }

    /** All three catalogues use the same terminal word, RETIRED. */
    private boolean isRetired(Status status) {
        return status != null && NotraiusStatuses.RETIRED.equals(status.getCode());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Audit and text helpers
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Append one audit entry with before/after data. Fail-soft by contract: a
     * catalogue write must never be lost because an audit row could not be
     * written (the interface's own doctrine).
     */
    private void audit(String entityCode, String activityCode, UUID referenceId,
                       String operation, Object before, Object after) {
        auditTrail.record(entityCode, activityCode, referenceId, operation, before, after);
    }

    /** Codes are machine identity: trimmed and upper-cased before comparison. */
    private String normalise(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
