package com.notraius.modules.config.reference.controller;

import com.notraius.common.dto.bulk.BulkIdsRequest;
import com.notraius.common.dto.bulk.BulkItemsRequest;
import com.notraius.common.dto.bulk.BulkResponse;
import com.notraius.common.services.BulkRunner;
import com.notraius.config.web.PublicReadThrottle;
import com.notraius.modules.core.common.constants.NotraiusActivities;
import com.notraius.modules.config.reference.dto.request.AmenityCreateRequest;
import com.notraius.modules.config.reference.dto.request.AmenityUpdateRequest;
import com.notraius.modules.config.reference.dto.request.DurationCreateRequest;
import com.notraius.modules.config.reference.dto.request.DurationUpdateRequest;
import com.notraius.modules.config.reference.dto.request.TypeCreateRequest;
import com.notraius.modules.config.reference.dto.request.TypeReparentRequest;
import com.notraius.modules.config.reference.dto.request.TypeUpdateRequest;
import com.notraius.modules.config.reference.dto.response.AmenityResponse;
import com.notraius.modules.config.reference.dto.response.DurationResponse;
import com.notraius.modules.config.reference.dto.response.TypeResponse;
import com.notraius.modules.config.reference.service.ReferenceService;
import jakarta.validation.Valid;
import org.primarion.module.utils.http.PaginatedResponse;
import org.primarion.module.utils.security.SecurityContextUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The config module's reference sub-module — one controller for its three
 * catalogues (types, durations, amenities), grouped by the operator because
 * they are one governance shape.
 * <p>
 * <b>The tier split, expressed the way every module in this plan expresses
 * it.</b> The catalogue <i>pages</i> are gated to the personal tier and above so
 * a user can fill their dropdowns and filters; the <i>by-id</i> reads (single
 * and batch) are public doors and therefore carry no {@code @PreAuthorize} at
 * all — a logged-out visitor's property page resolves its type, its duration
 * and its amenities by id, and there is no principal for an authority gate to
 * resolve. The servlet chain's {@code PUBLIC_GET_ENDPOINTS} entry is their gate;
 * each call pays an IP-scoped {@link PublicReadThrottle} hit. Catalogue writes
 * are gated to the admin tier.
 * The two user-tier writes are the ones the plan hands to users by ruling:
 * creating a duration (a personal user mints their own calendar combination
 * instead of queueing behind an admin) and proposing an amenity (a community
 * act, moderated afterwards).
 * <p>
 * Nothing is hardcoded in a definition file: {@code PermissionSeeder} derives
 * the permission rows from these annotations, so the declarations below are
 * the truth.
 * <p>
 * Each catalogue owns its own activity family ({@code TYPE}, {@code DURATION},
 * {@code AMENITY}). That is not decoration — the one-pair law allows exactly
 * one policy per {@code (activity, operation)} pair, and these catalogues
 * disagree: type writes are admin-tier while duration creation is
 * personal-tier, so a shared family with a shared {@code CREATE} operation
 * would declare two policies on one pair and refuse to boot.
 */
@RestController
@RequestMapping("/api/v1/notraius/config/reference")
public class ReferenceController {

    private final ReferenceService referenceService;
    private final BulkRunner bulkRunner;
    private final PublicReadThrottle publicReadThrottle;

    public ReferenceController(ReferenceService referenceService, BulkRunner bulkRunner,
                               PublicReadThrottle publicReadThrottle) {
        this.referenceService = referenceService;
        this.bulkRunner = bulkRunner;
        this.publicReadThrottle = publicReadThrottle;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TYPES
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Browse a types catalogue. Filter by {@code entityCode} to get one
     * namespace (PROPERTY kinds, pricing cadences, duration units, …); only
     * ACTIVE rows unless {@code activeOnly=false}, so a selection UI never
     * offers a retired kind.
     */
    @GetMapping("/types")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'TYPE', 'LIST')")
    public ResponseEntity<Page<TypeResponse>> listTypes(
            @RequestParam(required = false) String entityCode,
            @RequestParam(required = false) UUID parentId,
            @RequestParam(required = false) Boolean activeOnly,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Pageable pageable = PaginatedResponse.of(page, size,
                Sort.by(Sort.Direction.ASC, "code").and(Sort.by("id")));
        return ResponseEntity.ok(referenceService.listTypes(entityCode, parentId, activeOnly, search, pageable));
    }

    /** Read a type by id — any status, because history must keep resolving. */
    @GetMapping("/types/{typeId}")
    public ResponseEntity<TypeResponse> getType(@PathVariable UUID typeId) {
        publicReadThrottle.charge(NotraiusActivities.Types.GET);
        return ResponseEntity.ok(referenceService.getType(typeId));
    }

    @PostMapping("/types")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'TYPE', 'CREATE')")
    public ResponseEntity<TypeResponse> createType(@Valid @RequestBody TypeCreateRequest request) {
        UUID userId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED).body(referenceService.createType(request, userId));
    }

    /** Edit the label and description. The code is immutable by design. */
    @PutMapping("/types/{typeId}")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'TYPE', 'UPDATE')")
    public ResponseEntity<TypeResponse> updateType(@PathVariable UUID typeId,
                                                   @Valid @RequestBody TypeUpdateRequest request) {
        return ResponseEntity.ok(referenceService.updateType(typeId, request));
    }

    /** Move the node; a null parent makes it a root. */
    @PutMapping("/types/{typeId}/parent")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'TYPE', 'REPARENT')")
    public ResponseEntity<TypeResponse> reparentType(@PathVariable UUID typeId,
                                                     @Valid @RequestBody TypeReparentRequest request) {
        return ResponseEntity.ok(referenceService.reparentType(typeId, request));
    }

    @PostMapping("/types/{typeId}/retire")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'TYPE', 'RETIRE')")
    public ResponseEntity<TypeResponse> retireType(@PathVariable UUID typeId) {
        return ResponseEntity.ok(referenceService.retireType(typeId));
    }

    @DeleteMapping("/types/{typeId}")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'TYPE', 'DELETE')")
    public ResponseEntity<Void> deleteType(@PathVariable UUID typeId) {
        referenceService.deleteType(typeId);
        return ResponseEntity.noContent().build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DURATIONS
    // ═══════════════════════════════════════════════════════════════════════

    @GetMapping("/durations")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'DURATION', 'LIST')")
    public ResponseEntity<Page<DurationResponse>> listDurations(
            @RequestParam(required = false) UUID typeId,
            @RequestParam(required = false) Boolean activeOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        // value repeats across units (MONTH + 1 exists under several kinds), so id
        // is what makes the order total and the page boundary stable.
        Pageable pageable = PaginatedResponse.of(page, size,
                Sort.by(Sort.Direction.ASC, "value").and(Sort.by("id")));
        return ResponseEntity.ok(referenceService.listDurations(typeId, activeOnly, pageable));
    }

    @GetMapping("/durations/{durationId}")
    public ResponseEntity<DurationResponse> getDuration(@PathVariable UUID durationId) {
        publicReadThrottle.charge(NotraiusActivities.Durations.GET);
        return ResponseEntity.ok(referenceService.getDuration(durationId));
    }

    /**
     * Mint a duration — a user-tier write by ruling: a personal user who needs
     * WEEK + 2 mints it themselves. (type, value) is the identity, so the
     * same pair twice is refused.
     */
    @PostMapping("/durations")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'DURATION', 'CREATE')")
    public ResponseEntity<DurationResponse> createDuration(@Valid @RequestBody DurationCreateRequest request) {
        UUID userId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED).body(referenceService.createDuration(request, userId));
    }

    @PutMapping("/durations/{durationId}")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'DURATION', 'UPDATE')")
    public ResponseEntity<DurationResponse> updateDuration(@PathVariable UUID durationId,
                                                           @Valid @RequestBody DurationUpdateRequest request) {
        return ResponseEntity.ok(referenceService.updateDuration(durationId, request));
    }

    @PostMapping("/durations/{durationId}/retire")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'DURATION', 'RETIRE')")
    public ResponseEntity<DurationResponse> retireDuration(@PathVariable UUID durationId) {
        return ResponseEntity.ok(referenceService.retireDuration(durationId));
    }

    @DeleteMapping("/durations/{durationId}")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'DURATION', 'DELETE')")
    public ResponseEntity<Void> deleteDuration(@PathVariable UUID durationId) {
        referenceService.deleteDuration(durationId);
        return ResponseEntity.noContent().build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AMENITIES
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * The live catalogue a property selects from — ACTIVE rows only. With
     * {@code mine=true} it lists the caller's own submissions instead, in any
     * status, so a proposer can find the row they proposed.
     */
    @GetMapping("/amenities")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'AMENITY', 'LIST')")
    public ResponseEntity<Page<AmenityResponse>> listAmenities(
            @RequestParam(required = false) UUID typeId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Pageable pageable = PaginatedResponse.of(page, size,
                Sort.by(Sort.Direction.ASC, "code").and(Sort.by("id")));
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(referenceService.listAmenities(typeId, search, mine, callerId, pageable));
    }

    /**
     * The moderation queue: every amenity that is not live, whatever its
     * status. A separate surface and a separate permission pair from the live
     * list, because it is a staff view of other people's proposals.
     */
    @GetMapping("/amenities/submissions")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'AMENITY', 'LIST_SUBMISSIONS')")
    public ResponseEntity<Page<AmenityResponse>> listAmenitySubmissions(
            @RequestParam(required = false) UUID typeId,
            @RequestParam(required = false) String statusCode,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Pageable pageable = PaginatedResponse.of(page, size,
                Sort.by(Sort.Direction.ASC, "code").and(Sort.by("id")));
        return ResponseEntity.ok(referenceService.listAmenitySubmissions(typeId, statusCode, search, pageable));
    }

    @GetMapping("/amenities/{amenityId}")
    public ResponseEntity<AmenityResponse> getAmenity(@PathVariable UUID amenityId) {
        publicReadThrottle.charge(NotraiusActivities.Amenities.GET);
        return ResponseEntity.ok(referenceService.getAmenity(amenityId, SecurityContextUtils.getCurrentUserId()));
    }

    /**
     * Propose an amenity — any user. The row lands in PENDING and belongs to
     * its proposer; when a staff account is the creator it is ACTIVE at once,
     * because staff birth is staff approval.
     */
    @PostMapping("/amenities")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'AMENITY', 'PROPOSE')")
    public ResponseEntity<AmenityResponse> createAmenity(@Valid @RequestBody AmenityCreateRequest request) {
        UUID userId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED).body(referenceService.createAmenity(request, userId));
    }

    /** Edit a live amenity. Staff only: the live catalogue is a platform act. */
    @PutMapping("/amenities/{amenityId}")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'AMENITY', 'UPDATE')")
    public ResponseEntity<AmenityResponse> updateAmenity(@PathVariable UUID amenityId,
                                                         @Valid @RequestBody AmenityUpdateRequest request) {
        return ResponseEntity.ok(referenceService.updateAmenity(amenityId, request));
    }

    /**
     * Withdraw a pending proposal. Open at the personal tier because the act
     * belongs to the proposer, and the service checks that ownership — staff
     * withdraw nobody's proposal; they reject it.
     */
    @PostMapping("/amenities/{amenityId}/cancel")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'AMENITY', 'CANCEL')")
    public ResponseEntity<AmenityResponse> cancelAmenity(@PathVariable UUID amenityId) {
        UUID userId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(referenceService.cancelAmenity(amenityId, userId));
    }

    @PostMapping("/amenities/{amenityId}/confirm")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'AMENITY', 'CONFIRM')")
    public ResponseEntity<AmenityResponse> confirmAmenity(@PathVariable UUID amenityId) {
        return ResponseEntity.ok(referenceService.confirmAmenity(amenityId));
    }

    @PostMapping("/amenities/{amenityId}/reject")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'AMENITY', 'REJECT')")
    public ResponseEntity<AmenityResponse> rejectAmenity(@PathVariable UUID amenityId) {
        return ResponseEntity.ok(referenceService.rejectAmenity(amenityId));
    }

    /** Switch a live amenity off — reversible, and estate-wide (cascade hook). */
    @PostMapping("/amenities/{amenityId}/disable")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'AMENITY', 'DISABLE')")
    public ResponseEntity<AmenityResponse> disableAmenity(@PathVariable UUID amenityId) {
        return ResponseEntity.ok(referenceService.disableAmenity(amenityId));
    }

    /** Switch it back on, restoring only what the catalogue suppressed. */
    @PostMapping("/amenities/{amenityId}/enable")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'AMENITY', 'ENABLE')")
    public ResponseEntity<AmenityResponse> enableAmenity(@PathVariable UUID amenityId) {
        return ResponseEntity.ok(referenceService.enableAmenity(amenityId));
    }

    /** The rare, checked exit: refused while a property still holds the amenity. */
    @PostMapping("/amenities/{amenityId}/retire")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'AMENITY', 'RETIRE')")
    public ResponseEntity<AmenityResponse> retireAmenity(@PathVariable UUID amenityId) {
        return ResponseEntity.ok(referenceService.retireAmenity(amenityId));
    }

    @DeleteMapping("/amenities/{amenityId}")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'AMENITY', 'DELETE')")
    public ResponseEntity<Void> deleteAmenity(@PathVariable UUID amenityId) {
        referenceService.deleteAmenity(amenityId);
        return ResponseEntity.noContent().build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // BULK DOORS — one round trip for the catalogue waves
    // ═══════════════════════════════════════════════════════════════════════
    // An admin seeds a catalogue in waves and moderates a queue in batches; a
    // proposer submits several amenities at once. Every door below calls the
    // single-item service method once per id in its own transaction, so the
    // admin tier, the user tier, the in-use gates (`AMENITY_IN_USE`,
    // `TYPE_IN_USE`) and the moderation state machine all still decide per row.

    @GetMapping("/types/batch")
    public ResponseEntity<BulkResponse<TypeResponse>> batchGetTypes(@RequestParam List<UUID> ids) {
        publicReadThrottle.charge(NotraiusActivities.Types.GET);
        return ResponseEntity.ok(bulkRunner.read(BulkIdsRequest.checked(ids), referenceService::getType));
    }

    @PostMapping("/types/bulk")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'TYPE', 'CREATE')")
    public ResponseEntity<BulkResponse<TypeResponse>> bulkCreateTypes(
            @Valid @RequestBody BulkItemsRequest<TypeCreateRequest> request) {
        UUID userId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(bulkRunner.runPayloads(request.getItems(),
                        item -> referenceService.createType(item, userId)));
    }

    @PostMapping("/types/bulk/retire")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'TYPE', 'RETIRE')")
    public ResponseEntity<BulkResponse<TypeResponse>> bulkRetireTypes(
            @Valid @RequestBody BulkIdsRequest request) {
        return ResponseEntity.ok(bulkRunner.run(request.getIds(), referenceService::retireType));
    }

    @DeleteMapping("/types/bulk")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'TYPE', 'DELETE')")
    public ResponseEntity<BulkResponse<Void>> bulkDeleteTypes(@RequestParam List<UUID> ids) {
        return ResponseEntity.ok(bulkRunner.run(BulkIdsRequest.checked(ids), id -> {
            referenceService.deleteType(id);
            return null;
        }));
    }

    @GetMapping("/durations/batch")
    public ResponseEntity<BulkResponse<DurationResponse>> batchGetDurations(@RequestParam List<UUID> ids) {
        publicReadThrottle.charge(NotraiusActivities.Durations.GET);
        return ResponseEntity.ok(bulkRunner.read(BulkIdsRequest.checked(ids), referenceService::getDuration));
    }

    @PostMapping("/durations/bulk")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'DURATION', 'CREATE')")
    public ResponseEntity<BulkResponse<DurationResponse>> bulkCreateDurations(
            @Valid @RequestBody BulkItemsRequest<DurationCreateRequest> request) {
        UUID userId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(bulkRunner.runPayloads(request.getItems(),
                        item -> referenceService.createDuration(item, userId)));
    }

    @PostMapping("/durations/bulk/retire")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'DURATION', 'RETIRE')")
    public ResponseEntity<BulkResponse<DurationResponse>> bulkRetireDurations(
            @Valid @RequestBody BulkIdsRequest request) {
        return ResponseEntity.ok(bulkRunner.run(request.getIds(), referenceService::retireDuration));
    }

    @DeleteMapping("/durations/bulk")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'DURATION', 'DELETE')")
    public ResponseEntity<BulkResponse<Void>> bulkDeleteDurations(@RequestParam List<UUID> ids) {
        return ResponseEntity.ok(bulkRunner.run(BulkIdsRequest.checked(ids), id -> {
            referenceService.deleteDuration(id);
            return null;
        }));
    }

    @GetMapping("/amenities/batch")
    public ResponseEntity<BulkResponse<AmenityResponse>> batchGetAmenities(@RequestParam List<UUID> ids) {
        publicReadThrottle.charge(NotraiusActivities.Amenities.GET);
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.read(BulkIdsRequest.checked(ids),
                id -> referenceService.getAmenity(id, callerId)));
    }

    /** Propose many amenities — the community act, batched by the form. */
    @PostMapping("/amenities/bulk")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'AMENITY', 'PROPOSE')")
    public ResponseEntity<BulkResponse<AmenityResponse>> bulkProposeAmenities(
            @Valid @RequestBody BulkItemsRequest<AmenityCreateRequest> request) {
        UUID userId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(bulkRunner.runPayloads(request.getItems(),
                        item -> referenceService.createAmenity(item, userId)));
    }

    @PostMapping("/amenities/bulk/cancel")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'AMENITY', 'CANCEL')")
    public ResponseEntity<BulkResponse<AmenityResponse>> bulkCancelAmenities(
            @Valid @RequestBody BulkIdsRequest request) {
        UUID userId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.run(request.getIds(),
                id -> referenceService.cancelAmenity(id, userId)));
    }

    @PostMapping("/amenities/bulk/confirm")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'AMENITY', 'CONFIRM')")
    public ResponseEntity<BulkResponse<AmenityResponse>> bulkConfirmAmenities(
            @Valid @RequestBody BulkIdsRequest request) {
        return ResponseEntity.ok(bulkRunner.run(request.getIds(), referenceService::confirmAmenity));
    }

    @PostMapping("/amenities/bulk/reject")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'AMENITY', 'REJECT')")
    public ResponseEntity<BulkResponse<AmenityResponse>> bulkRejectAmenities(
            @Valid @RequestBody BulkIdsRequest request) {
        return ResponseEntity.ok(bulkRunner.run(request.getIds(), referenceService::rejectAmenity));
    }

    @PostMapping("/amenities/bulk/disable")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'AMENITY', 'DISABLE')")
    public ResponseEntity<BulkResponse<AmenityResponse>> bulkDisableAmenities(
            @Valid @RequestBody BulkIdsRequest request) {
        return ResponseEntity.ok(bulkRunner.run(request.getIds(), referenceService::disableAmenity));
    }

    @PostMapping("/amenities/bulk/enable")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'AMENITY', 'ENABLE')")
    public ResponseEntity<BulkResponse<AmenityResponse>> bulkEnableAmenities(
            @Valid @RequestBody BulkIdsRequest request) {
        return ResponseEntity.ok(bulkRunner.run(request.getIds(), referenceService::enableAmenity));
    }

    @PostMapping("/amenities/bulk/retire")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'AMENITY', 'RETIRE')")
    public ResponseEntity<BulkResponse<AmenityResponse>> bulkRetireAmenities(
            @Valid @RequestBody BulkIdsRequest request) {
        return ResponseEntity.ok(bulkRunner.run(request.getIds(), referenceService::retireAmenity));
    }

    @DeleteMapping("/amenities/bulk")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'AMENITY', 'DELETE')")
    public ResponseEntity<BulkResponse<Void>> bulkDeleteAmenities(@RequestParam List<UUID> ids) {
        return ResponseEntity.ok(bulkRunner.run(BulkIdsRequest.checked(ids), id -> {
            referenceService.deleteAmenity(id);
            return null;
        }));
    }
}
