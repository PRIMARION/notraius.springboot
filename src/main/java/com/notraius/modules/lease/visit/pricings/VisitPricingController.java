package com.notraius.modules.lease.visit.pricings;

import com.notraius.common.dto.bulk.BulkIdsRequest;
import com.notraius.common.dto.bulk.BulkItemsRequest;
import com.notraius.common.dto.bulk.BulkResponse;
import com.notraius.common.services.logs.BulkRunner;
import com.notraius.config.web.PublicReadThrottle;
import com.notraius.modules.core.core.common.constants.NotraiusActivities;
import com.notraius.modules.lease.visit.pricings.dto.request.VisitPricingCreateRequest;
import com.notraius.modules.lease.visit.pricings.dto.request.VisitPricingUpdateRequest;
import com.notraius.modules.lease.visit.pricings.dto.VisitPricingResponse;
import com.notraius.modules.lease.visit.pricings.VisitPricingService;
import jakarta.validation.Valid;
import org.primarion.module.utils.utils.security.security.SecurityContextUtils;
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
 * The owner's visit-price surface (PLAN.md 5.1). One physical and one virtual
 * offer per property; pause is the reversible switch, deletion the exception for
 * an offer nothing was sold at.
 * <p>
 * <b>Both reads are public doors</b> and declare the {@code UNAUTHENTICATED}
 * rung: the visit price is part of the listing a logged-out visitor compares,
 * and the declaration — read by {@code DoorRegistry} — is the gate. Each call
 * pays an IP-scoped {@link PublicReadThrottle} hit.
 */
@RestController
@RequestMapping("/api/v1/notraius/visit-pricings")
public class VisitPricingController {

    private final VisitPricingService visitPricingService;
    private final BulkRunner bulkRunner;
    private final PublicReadThrottle publicReadThrottle;

    public VisitPricingController(VisitPricingService visitPricingService, BulkRunner bulkRunner,
                                  PublicReadThrottle publicReadThrottle) {
        this.visitPricingService = visitPricingService;
        this.bulkRunner = bulkRunner;
        this.publicReadThrottle = publicReadThrottle;
    }

    @GetMapping
    @PreAuthorize("@authz.authority('UNAUTHENTICATED', 'GTE', 'PUBLIC', 'VISIT_PRICING_LIST')")
    public ResponseEntity<List<VisitPricingResponse>> listPricings(@RequestParam UUID propertyId) {
        publicReadThrottle.charge(NotraiusActivities.Visits.PRICING_LIST);
        return ResponseEntity.ok(visitPricingService.listPricings(propertyId,
                SecurityContextUtils.getCurrentUserId()));
    }

    @PostMapping
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'VISIT_PRICINGS', 'CREATE')")
    public ResponseEntity<VisitPricingResponse> createPricing(@RequestParam UUID propertyId,
                                                             @Valid @RequestBody VisitPricingCreateRequest request) {
        VisitPricingResponse response = visitPricingService.createPricing(propertyId, request,
                SecurityContextUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{pricingId}")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'VISIT_PRICINGS', 'UPDATE')")
    public ResponseEntity<VisitPricingResponse> updatePricing(@PathVariable UUID pricingId,
                                                             @Valid @RequestBody VisitPricingUpdateRequest request) {
        return ResponseEntity.ok(visitPricingService.updatePricing(pricingId, request,
                SecurityContextUtils.getCurrentUserId()));
    }

    @PostMapping("/{pricingId}/pause")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'VISIT_PRICINGS', 'PAUSE')")
    public ResponseEntity<VisitPricingResponse> pausePricing(@PathVariable UUID pricingId) {
        return ResponseEntity.ok(visitPricingService.pausePricing(pricingId,
                SecurityContextUtils.getCurrentUserId()));
    }

    @PostMapping("/{pricingId}/resume")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'VISIT_PRICINGS', 'RESUME')")
    public ResponseEntity<VisitPricingResponse> resumePricing(@PathVariable UUID pricingId) {
        return ResponseEntity.ok(visitPricingService.resumePricing(pricingId,
                SecurityContextUtils.getCurrentUserId()));
    }

    @DeleteMapping("/{pricingId}")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'VISIT_PRICINGS', 'DELETE')")
    public ResponseEntity<Void> deletePricing(@PathVariable UUID pricingId) {
        visitPricingService.deletePricing(pricingId, SecurityContextUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Bulk doors — the owner sets both offers in one round trip
    // ═══════════════════════════════════════════════════════════════════════

    @GetMapping("/batch")
    @PreAuthorize("@authz.authority('UNAUTHENTICATED', 'GTE', 'PUBLIC', 'VISIT_PRICING_BATCH')")
    public ResponseEntity<BulkResponse<List<VisitPricingResponse>>> batchList(
            @RequestParam List<UUID> propertyIds) {
        publicReadThrottle.charge(NotraiusActivities.Visits.PRICING_LIST);
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.read(BulkIdsRequest.checked(propertyIds),
                id -> visitPricingService.listPricings(id, callerId)));
    }

    @PostMapping("/bulk")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'VISIT_PRICINGS', 'CREATE')")
    public ResponseEntity<BulkResponse<VisitPricingResponse>> bulkCreate(
            @RequestParam UUID propertyId,
            @Valid @RequestBody BulkItemsRequest<VisitPricingCreateRequest> request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(bulkRunner.runPayloads(request.getItems(),
                        item -> visitPricingService.createPricing(propertyId, item, callerId)));
    }

    @PostMapping("/bulk/pause")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'VISIT_PRICINGS', 'PAUSE')")
    public ResponseEntity<BulkResponse<VisitPricingResponse>> bulkPause(
            @Valid @RequestBody BulkIdsRequest request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.run(request.getIds(),
                id -> visitPricingService.pausePricing(id, callerId)));
    }

    @PostMapping("/bulk/resume")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'VISIT_PRICINGS', 'RESUME')")
    public ResponseEntity<BulkResponse<VisitPricingResponse>> bulkResume(
            @Valid @RequestBody BulkIdsRequest request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.run(request.getIds(),
                id -> visitPricingService.resumePricing(id, callerId)));
    }

    @DeleteMapping("/bulk")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'VISIT_PRICINGS', 'DELETE')")
    public ResponseEntity<BulkResponse<Void>> bulkDelete(@RequestParam List<UUID> ids) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.run(BulkIdsRequest.checked(ids), id -> {
            visitPricingService.deletePricing(id, callerId);
            return null;
        }));
    }
}
