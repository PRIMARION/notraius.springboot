package com.notraius.modules.lease.asset.property;

import com.notraius.common.dto.bulk.BulkIdsRequest;
import com.notraius.common.dto.bulk.BulkItemsRequest;
import com.notraius.common.dto.bulk.BulkResponse;
import com.notraius.common.dto.RevisionSummaryResponse;
import com.notraius.common.services.logs.BulkRunner;
import com.notraius.config.web.PublicReadThrottle;
import com.notraius.modules.core.core.common.constants.NotraiusActivities;
import com.notraius.modules.lease.asset.property.dto.request.PropertyAmenityRequest;
import com.notraius.modules.lease.asset.property.dto.request.PropertyCreateRequest;
import com.notraius.modules.lease.asset.property.dto.request.PropertyLocationRequest;
import com.notraius.modules.lease.asset.property.dto.request.PropertyUpdateRequest;
import com.notraius.modules.lease.asset.property.dto.response.PropertyAmenityResponse;
import com.notraius.modules.lease.asset.property.dto.response.PropertyResponse;
import com.notraius.modules.lease.asset.property.dto.response.TenancyResponse;
import com.notraius.modules.lease.asset.property.PropertyService;
import jakarta.validation.Valid;
import org.primarion.module.utils.utils.http.PaginatedResponse;
import org.primarion.module.utils.utils.security.security.SecurityContextUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import com.notraius.database.entities.lease.reference.amenity.Amenity;

/**
 * The asset module's property sub-module (PLAN.md 4.1).
 * <p>
 * Reads separate the two audiences by URL, not by a query flag: the market page
 * lists published properties only, the owner's page lists the caller's own rows
 * in every status. Writes are the owner's, except suspension and reinstatement,
 * which are moderation's — and moderation is never given ownership.
 * <p>
 * <b>Reads are public (UPGRADE.md D1/D2).</b> The market list, the by-id read,
 * the amenity holdings and the tenancy window declare the
 * {@code UNAUTHENTICATED} rung — that declaration, read by {@code DoorRegistry},
 * is what requires the platform public key and admits the door in the servlet
 * chain; the
 * annotation is the gate. Each read still pays an IP-scoped
 * {@link PublicReadThrottle} hit. Drafts, pauses and suspensions stay
 * invisible to everyone but the owner and staff, and every write stays
 * session-bound.
 * <p>
 * <b>No currency, no state, no address in any request body.</b> The anchors are
 * derived once by {@code PUT /{propertyId}/location}; there is deliberately no
 * endpoint to edit them afterwards.
 * <p>
 * <b>No hard delete.</b> {@code DELETE} is the owner's soft exit (DELETED), so
 * the property's pricings, terms and history survive it.
 */
@RestController
@RequestMapping("/api/v1/notraius/asset/properties")
public class PropertyController {

    private final PropertyService propertyService;
    private final BulkRunner bulkRunner;
    private final PublicReadThrottle publicReadThrottle;

    public PropertyController(PropertyService propertyService, BulkRunner bulkRunner,
                              PublicReadThrottle publicReadThrottle) {
        this.propertyService = propertyService;
        this.bulkRunner = bulkRunner;
        this.publicReadThrottle = publicReadThrottle;
    }

    // ── Market and owner pages ────────────────────────────────────────────

    /**
     * The market page: published properties, filterable by kind, state and
     * text, with each row's engagement facts (views, live reactions, standing
     * comments) riding along. {@code ?sort=popular} reorders by those
     * impressions — popularity reorders, never filters; the default
     * {@code freshness} keeps the newest first.
     * <p>
     * Public door — the handler declares {@code UNAUTHENTICATED} (see the class note).
     */
    @GetMapping
    @PreAuthorize("@authz.authority('UNAUTHENTICATED', 'GTE', 'PUBLIC', 'PROPERTY_LIST')")
    public ResponseEntity<Page<PropertyResponse>> listProperties(
            @RequestParam(required = false) UUID typeId,
            @RequestParam(required = false) UUID stateId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = PropertyService.SORT_FRESHNESS) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        publicReadThrottle.charge(NotraiusActivities.Properties.LIST);
        // The id tie-break is not decoration: a bulk create stamps many rows with
        // the same instant, and a sort on the instant alone can repeat one row on
        // page two while skipping another (Postgres is free to order ties
        // differently per query).
        Pageable pageable = PaginatedResponse.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        return ResponseEntity.ok(propertyService.listPublished(typeId, stateId, search, sort, pageable));
    }

    /** The owner's page: every status of the caller's own properties. */
    @GetMapping("/mine")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PROPERTY', 'LIST_MINE')")
    public ResponseEntity<Page<PropertyResponse>> listMyProperties(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PaginatedResponse.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        return ResponseEntity.ok(propertyService.listMine(
                SecurityContextUtils.getCurrentUserId(), status, search, pageable));
    }

    /**
     * Public door — {@code UNAUTHENTICATED}; the row's own visibility decides.
     * <p>
     * {@code ?version=N} asks for the listing as that revision stated it instead
     * of as it stands now (the row keeps only its newest truth; the answer is
     * reconstructed from the audit chain). Absent, or equal to the row's current
     * version, is the live read. History is the owner's and moderation's: anyone
     * else gets the same 404 an absent listing gets, because a past revision can
     * hold what the present row no longer says.
     * <p>
     * <b>The client pairing.</b> This read is the front door of the attention
     * loop: a client that renders this page should open a view
     * ({@code POST /engagement/PROPERTY/{id}/views}) and close it when the
     * visitor leaves ({@code PATCH /engagement/views/{stateId}/close}) — the
     * close is what records the dwell, and a dwell at or above the threshold
     * is what nudges the visitor's affinity.
     */
    @GetMapping("/{propertyId}")
    @PreAuthorize("@authz.authority('UNAUTHENTICATED', 'GTE', 'PUBLIC', 'PROPERTY_GET')")
    public ResponseEntity<PropertyResponse> getProperty(
            @PathVariable UUID propertyId,
            @RequestParam(required = false) Integer version) {
        publicReadThrottle.charge(NotraiusActivities.Properties.GET);
        return ResponseEntity.ok(propertyService.getProperty(propertyId,
                SecurityContextUtils.getCurrentUserId(), version));
    }

    /**
     * Which revisions this listing has had — discovery for {@code ?version=}.
     * <p>
     * Public door in shape, owner-or-staff in substance: the service applies the
     * same visibility and history rules as the single read, so anyone else gets
     * the 404 an absent listing gets. The throttle is the read throttle, because
     * this is a read of the same record.
     */
    @GetMapping("/{propertyId}/revisions")
    @PreAuthorize("@authz.authority('UNAUTHENTICATED', 'GTE', 'PUBLIC', 'PROPERTY_REVISIONS')")
    public ResponseEntity<List<RevisionSummaryResponse>> listRevisions(@PathVariable UUID propertyId) {
        publicReadThrottle.charge(NotraiusActivities.Properties.GET);
        return ResponseEntity.ok(propertyService.listRevisions(propertyId,
                SecurityContextUtils.getCurrentUserId()));
    }

    /**
     * The public occupancy window (UPGRADE.md D2 / I5): "someone lives here,
     * until &lt;date&gt;". Dates only — no tenant, no lease id, no money. A
     * property with no live tenancy answers with its status and an empty
     * window, so this door never becomes a second existence oracle.
     */
    @GetMapping("/{propertyId}/tenancy")
    @PreAuthorize("@authz.authority('UNAUTHENTICATED', 'GTE', 'PUBLIC', 'PROPERTY_TENANCY')")
    public ResponseEntity<TenancyResponse> getTenancy(@PathVariable UUID propertyId) {
        publicReadThrottle.charge(NotraiusActivities.Properties.GET);
        return ResponseEntity.ok(propertyService.readTenancy(propertyId,
                SecurityContextUtils.getCurrentUserId()));
    }

    // ── Create and edit ───────────────────────────────────────────────────

    @PostMapping
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PROPERTY', 'CREATE')")
    public ResponseEntity<PropertyResponse> createProperty(@Valid @RequestBody PropertyCreateRequest request) {
        PropertyResponse response = propertyService.createProperty(request,
                SecurityContextUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{propertyId}")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PROPERTY', 'UPDATE')")
    public ResponseEntity<PropertyResponse> updateProperty(@PathVariable UUID propertyId,
                                                          @Valid @RequestBody PropertyUpdateRequest request) {
        return ResponseEntity.ok(propertyService.updateProperty(propertyId, request,
                SecurityContextUtils.getCurrentUserId()));
    }

    /**
     * The one-time anchor. Sending it twice is refused by name
     * ({@code PROPERTY_LOCATION_IMMUTABLE}) rather than silently ignored.
     */
    @PutMapping("/{propertyId}/location")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PROPERTY', 'LOCATION')")
    public ResponseEntity<PropertyResponse> setLocation(@PathVariable UUID propertyId,
                                                       @Valid @RequestBody PropertyLocationRequest request) {
        return ResponseEntity.ok(propertyService.setLocation(propertyId, request,
                SecurityContextUtils.getCurrentUserId()));
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @PostMapping("/{propertyId}/publish")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PROPERTY', 'PUBLISH')")
    public ResponseEntity<PropertyResponse> publishProperty(@PathVariable UUID propertyId) {
        return ResponseEntity.ok(propertyService.publishProperty(propertyId,
                SecurityContextUtils.getCurrentUserId()));
    }

    @PostMapping("/{propertyId}/unpublish")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PROPERTY', 'UNPUBLISH')")
    public ResponseEntity<PropertyResponse> unpublishProperty(@PathVariable UUID propertyId) {
        return ResponseEntity.ok(propertyService.unpublishProperty(propertyId,
                SecurityContextUtils.getCurrentUserId()));
    }

    /** Moderation's stop — never the owner's. */
    @PostMapping("/{propertyId}/suspend")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'PROPERTY', 'SUSPEND')")
    public ResponseEntity<PropertyResponse> suspendProperty(@PathVariable UUID propertyId,
                                                           @RequestParam(required = false) String reason) {
        return ResponseEntity.ok(propertyService.suspendProperty(propertyId, reason));
    }

    /** Reinstatement is moderation's too, and lands PAUSED — the owner re-publishes. */
    @PostMapping("/{propertyId}/reinstate")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'PROPERTY', 'REINSTATE')")
    public ResponseEntity<PropertyResponse> reinstateProperty(@PathVariable UUID propertyId) {
        return ResponseEntity.ok(propertyService.reinstateProperty(propertyId));
    }

    /** The owner's soft exit. */
    @DeleteMapping("/{propertyId}")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PROPERTY', 'DELETE')")
    public ResponseEntity<Void> deleteProperty(@PathVariable UUID propertyId) {
        propertyService.deleteProperty(propertyId, SecurityContextUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    // ── Amenity holdings ──────────────────────────────────────────────────

    /** Public door — {@code UNAUTHENTICATED}; unpublished rows still answer 404. */
    @GetMapping("/{propertyId}/amenities")
    @PreAuthorize("@authz.authority('UNAUTHENTICATED', 'GTE', 'PUBLIC', 'PROPERTY_AMENITIES')")
    public ResponseEntity<List<PropertyAmenityResponse>> listAmenities(@PathVariable UUID propertyId) {
        publicReadThrottle.charge(NotraiusActivities.Properties.GET);
        return ResponseEntity.ok(propertyService.listAmenities(propertyId,
                SecurityContextUtils.getCurrentUserId()));
    }

    /** Select or re-value an amenity the property holds. */
    @PutMapping("/{propertyId}/amenities")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PROPERTY', 'AMENITY_SET')")
    public ResponseEntity<PropertyAmenityResponse> setAmenity(@PathVariable UUID propertyId,
                                                             @Valid @RequestBody PropertyAmenityRequest request) {
        return ResponseEntity.ok(propertyService.setAmenity(propertyId, request,
                SecurityContextUtils.getCurrentUserId()));
    }

    @PatchMapping("/{propertyId}/amenities/{amenityId}/disable")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PROPERTY', 'AMENITY_DISABLE')")
    public ResponseEntity<PropertyAmenityResponse> disableAmenity(@PathVariable UUID propertyId,
                                                                 @PathVariable UUID amenityId) {
        return ResponseEntity.ok(propertyService.disableAmenity(propertyId, amenityId,
                SecurityContextUtils.getCurrentUserId()));
    }

    @PatchMapping("/{propertyId}/amenities/{amenityId}/enable")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PROPERTY', 'AMENITY_ENABLE')")
    public ResponseEntity<PropertyAmenityResponse> enableAmenity(@PathVariable UUID propertyId,
                                                                @PathVariable UUID amenityId) {
        return ResponseEntity.ok(propertyService.enableAmenity(propertyId, amenityId,
                SecurityContextUtils.getCurrentUserId()));
    }

    @DeleteMapping("/{propertyId}/amenities/{amenityId}")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PROPERTY', 'AMENITY_DELETE')")
    public ResponseEntity<Void> deleteAmenity(@PathVariable UUID propertyId, @PathVariable UUID amenityId) {
        propertyService.deleteAmenity(propertyId, amenityId, SecurityContextUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Bulk doors — the same acts, N at a time
    // ═══════════════════════════════════════════════════════════════════════
    // Each door calls the single-item service method once per id, in its own
    // transaction: ownership, the publish completeness gates and the soft-exit
    // rules all still decide per row, and the answer says which rows they
    // refused. Moderation's suspend/reinstate stay single-item on purpose — a
    // bulk suspension is exactly the mass action the audit trail must not blur
    // into one line.

    /** Many properties in one round trip — each answers for its own visibility. */
    @GetMapping("/batch")
    @PreAuthorize("@authz.authority('UNAUTHENTICATED', 'GTE', 'PUBLIC', 'PROPERTY_BATCH')")
    public ResponseEntity<BulkResponse<PropertyResponse>> batchGet(@RequestParam List<UUID> ids) {
        publicReadThrottle.charge(NotraiusActivities.Properties.GET);
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.read(BulkIdsRequest.checked(ids),
                id -> propertyService.getProperty(id, callerId)));
    }

    /** Birth many drafts at once; each one's completeness is still its own. */
    @PostMapping("/bulk")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PROPERTY', 'CREATE')")
    public ResponseEntity<BulkResponse<PropertyResponse>> bulkCreate(
            @Valid @RequestBody BulkItemsRequest<PropertyCreateRequest> request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(bulkRunner.runPayloads(request.getItems(),
                        item -> propertyService.createProperty(item, callerId)));
    }

    /** Publish many — each row still has to clear the completeness gates alone. */
    @PostMapping("/bulk/publish")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PROPERTY', 'PUBLISH')")
    public ResponseEntity<BulkResponse<PropertyResponse>> bulkPublish(
            @Valid @RequestBody BulkIdsRequest request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.run(request.getIds(),
                id -> propertyService.publishProperty(id, callerId)));
    }

    @PostMapping("/bulk/unpublish")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PROPERTY', 'UNPUBLISH')")
    public ResponseEntity<BulkResponse<PropertyResponse>> bulkUnpublish(
            @Valid @RequestBody BulkIdsRequest request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.run(request.getIds(),
                id -> propertyService.unpublishProperty(id, callerId)));
    }

    @DeleteMapping("/bulk")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PROPERTY', 'DELETE')")
    public ResponseEntity<BulkResponse<Void>> bulkDelete(@RequestParam List<UUID> ids) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.run(BulkIdsRequest.checked(ids), id -> {
            propertyService.deleteProperty(id, callerId);
            return null;
        }));
    }

    /** Hold many amenities at once — the property form's natural shape. */
    @PutMapping("/{propertyId}/amenities/bulk")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PROPERTY', 'AMENITY_SET')")
    public ResponseEntity<BulkResponse<PropertyAmenityResponse>> bulkSetAmenities(
            @PathVariable UUID propertyId,
            @Valid @RequestBody BulkItemsRequest<PropertyAmenityRequest> request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.runPayloads(request.getItems(),
                item -> propertyService.setAmenity(propertyId, item, callerId)));
    }

    @PatchMapping("/{propertyId}/amenities/bulk/disable")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PROPERTY', 'AMENITY_DISABLE')")
    public ResponseEntity<BulkResponse<PropertyAmenityResponse>> bulkDisableAmenities(
            @PathVariable UUID propertyId, @Valid @RequestBody BulkIdsRequest request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.run(request.getIds(),
                id -> propertyService.disableAmenity(propertyId, id, callerId)));
    }

    @PatchMapping("/{propertyId}/amenities/bulk/enable")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PROPERTY', 'AMENITY_ENABLE')")
    public ResponseEntity<BulkResponse<PropertyAmenityResponse>> bulkEnableAmenities(
            @PathVariable UUID propertyId, @Valid @RequestBody BulkIdsRequest request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.run(request.getIds(),
                id -> propertyService.enableAmenity(propertyId, id, callerId)));
    }

    @DeleteMapping("/{propertyId}/amenities/bulk")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PROPERTY', 'AMENITY_DELETE')")
    public ResponseEntity<BulkResponse<Void>> bulkDeleteAmenities(
            @PathVariable UUID propertyId, @RequestParam List<UUID> ids) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.run(BulkIdsRequest.checked(ids), id -> {
            propertyService.deleteAmenity(propertyId, id, callerId);
            return null;
        }));
    }
}
