package com.notraius.modules.asset.pricing.controller;

import com.notraius.common.dto.bulk.BulkIdsRequest;
import com.notraius.common.dto.bulk.BulkItemsRequest;
import com.notraius.common.dto.bulk.BulkResponse;
import com.notraius.common.dto.responses.RevisionSummaryResponse;
import com.notraius.common.services.BulkRunner;
import com.notraius.config.web.PublicReadThrottle;
import com.notraius.modules.core.common.constants.NotraiusActivities;
import com.notraius.modules.asset.pricing.dto.request.PricingCreateRequest;
import com.notraius.modules.asset.pricing.dto.request.PricingUpdateRequest;
import com.notraius.modules.asset.pricing.dto.request.PricingWindowRequest;
import com.notraius.modules.asset.pricing.dto.response.PricingResponse;
import com.notraius.modules.asset.pricing.service.PricingService;
import jakarta.validation.Valid;
import org.primarion.module.utils.security.SecurityContextUtils;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The asset module's pricing sub-module (PLAN.md 4.2).
 * <p>
 * The row-set is small by design: at most one pricing per (property, type). A
 * price change is {@code PUT /{pricingId}/window} on the row that already exists
 * — which is why there is no "replace pricing" endpoint and why creating a
 * second pricing for the same type is a named refusal.
 * <p>
 * The window is its own endpoint because it is its own act: it moves the version,
 * reopens the market after an expiry, and is the only path back to LIVE from
 * EXPIRED.
 * <p>
 * <b>Both reads are public doors.</b> They declare the {@code UNAUTHENTICATED}
 * rung; {@code DoorRegistry} turns that one declaration into the skipped key
 * floor and the chain's {@code permitAll}. Each call pays an IP-scoped
 * {@link PublicReadThrottle} hit, and the service still hides a price that is
 * not live from a caller who may not see it.
 */
@RestController
@RequestMapping("/api/v1/notraius/asset/pricings")
public class PricingController {

    private final PricingService pricingService;
    private final BulkRunner bulkRunner;
    private final PublicReadThrottle publicReadThrottle;

    public PricingController(PricingService pricingService, BulkRunner bulkRunner,
                             PublicReadThrottle publicReadThrottle) {
        this.pricingService = pricingService;
        this.bulkRunner = bulkRunner;
        this.publicReadThrottle = publicReadThrottle;
    }

    /** Public door — the handler declares {@code UNAUTHENTICATED} (see the class note). */
    @GetMapping
    @PreAuthorize("@authz.authority('UNAUTHENTICATED', 'GTE', 'PUBLIC', 'PRICING_LIST')")
    public ResponseEntity<List<PricingResponse>> listPricings(@RequestParam UUID propertyId) {
        publicReadThrottle.charge(NotraiusActivities.Pricings.LIST);
        return ResponseEntity.ok(pricingService.listPricings(propertyId,
                SecurityContextUtils.getCurrentUserId()));
    }

    /**
     * Public door — {@code UNAUTHENTICATED}; a non-live price is 404, not 403.
     * <p>
     * {@code ?version=N} asks what that revision of the price said, reconstructed
     * from the audit chain because the row keeps only its newest truth. History
     * stays with the owner and moderation — a retired amount is not the market's
     * current answer.
     */
    @GetMapping("/{pricingId}")
    @PreAuthorize("@authz.authority('UNAUTHENTICATED', 'GTE', 'PUBLIC', 'PRICING_GET')")
    public ResponseEntity<PricingResponse> getPricing(
            @PathVariable UUID pricingId,
            @RequestParam(required = false) Integer version) {
        publicReadThrottle.charge(NotraiusActivities.Pricings.GET);
        return ResponseEntity.ok(pricingService.getPricing(pricingId,
                SecurityContextUtils.getCurrentUserId(), version));
    }

    /**
     * The prices of one property that <b>answered at an instant</b> — the market
     * page's question ("what did a night cost on 3 March"), not a version read.
     * <p>
     * Public door, like the list: a published property shows the prices that
     * answered then, a draft answers 404. The answer is reconstructed from the
     * audit chain, because issuing a new window rewrites the row and the old
     * window survives only as history — the reader's law, and the same chain the
     * {@code ?version=} doors read.
     * <p>
     * {@code typeId} narrows the page to one commercial type; {@code at} is
     * required, because an as-of read without an instant is the live list.
     */
    @GetMapping("/answering")
    @PreAuthorize("@authz.authority('UNAUTHENTICATED', 'GTE', 'PUBLIC', 'PRICING_ANSWERING')")
    public ResponseEntity<List<PricingResponse>> answeringAt(
            @RequestParam UUID propertyId,
            @RequestParam(required = false) UUID typeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant at) {
        publicReadThrottle.charge(NotraiusActivities.Pricings.LIST);
        return ResponseEntity.ok(pricingService.answeringAt(propertyId, typeId, at,
                SecurityContextUtils.getCurrentUserId()));
    }

    /**
     * Which revisions this price has had — discovery for {@code ?version=}.
     * Same audience as a single revision (the owner and moderation); the service
     * answers everyone else with the 404 an absent price gets.
     */
    @GetMapping("/{pricingId}/revisions")
    @PreAuthorize("@authz.authority('UNAUTHENTICATED', 'GTE', 'PUBLIC', 'PRICING_REVISIONS')")
    public ResponseEntity<List<RevisionSummaryResponse>> listRevisions(@PathVariable UUID pricingId) {
        publicReadThrottle.charge(NotraiusActivities.Pricings.GET);
        return ResponseEntity.ok(pricingService.listRevisions(pricingId,
                SecurityContextUtils.getCurrentUserId()));
    }

    @PostMapping
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PRICING', 'CREATE')")
    public ResponseEntity<PricingResponse> createPricing(@RequestParam UUID propertyId,
                                                        @Valid @RequestBody PricingCreateRequest request) {
        PricingResponse response = pricingService.createPricing(propertyId, request,
                SecurityContextUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{pricingId}")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PRICING', 'UPDATE')")
    public ResponseEntity<PricingResponse> updatePricing(@PathVariable UUID pricingId,
                                                        @Valid @RequestBody PricingUpdateRequest request) {
        return ResponseEntity.ok(pricingService.updatePricing(pricingId, request,
                SecurityContextUtils.getCurrentUserId()));
    }

    /** Issue a new validity — the only way a price answers again after expiry. */
    @PutMapping("/{pricingId}/window")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PRICING', 'WINDOW')")
    public ResponseEntity<PricingResponse> issueWindow(@PathVariable UUID pricingId,
                                                      @Valid @RequestBody PricingWindowRequest request) {
        return ResponseEntity.ok(pricingService.issueWindow(pricingId, request,
                SecurityContextUtils.getCurrentUserId()));
    }

    @PostMapping("/{pricingId}/pause")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PRICING', 'PAUSE')")
    public ResponseEntity<PricingResponse> pausePricing(@PathVariable UUID pricingId) {
        return ResponseEntity.ok(pricingService.pausePricing(pricingId,
                SecurityContextUtils.getCurrentUserId()));
    }

    @PostMapping("/{pricingId}/resume")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PRICING', 'RESUME')")
    public ResponseEntity<PricingResponse> resumePricing(@PathVariable UUID pricingId) {
        return ResponseEntity.ok(pricingService.resumePricing(pricingId,
                SecurityContextUtils.getCurrentUserId()));
    }

    /** Removed only when nothing depends on it; otherwise pause is the exit. */
    @DeleteMapping("/{pricingId}")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PRICING', 'DELETE')")
    public ResponseEntity<Void> deletePricing(@PathVariable UUID pricingId) {
        pricingService.deletePricing(pricingId, SecurityContextUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Bulk doors — the same acts, N at a time
    // ═══════════════════════════════════════════════════════════════════════
    // Every door below calls the SAME service method its single-item sibling
    // calls, once per id, each in its own transaction (BulkRunner). Nothing is
    // re-checked here and nothing is skipped: the ownership rule, the
    // one-pricing-per-type refusal and the delete gate all still decide per
    // item, and the answer says which items they refused.

    /** The same read for many ids — one round trip, one answer per id. */
    @GetMapping("/batch")
    @PreAuthorize("@authz.authority('UNAUTHENTICATED', 'GTE', 'PUBLIC', 'PRICING_BATCH')")
    public ResponseEntity<BulkResponse<PricingResponse>> batchGet(@RequestParam List<UUID> ids) {
        publicReadThrottle.charge(NotraiusActivities.Pricings.GET);
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.read(BulkIdsRequest.checked(ids),
                id -> pricingService.getPricing(id, callerId)));
    }

    /** Create many prices at once (one per type, as always) for one property. */
    @PostMapping("/bulk")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PRICING', 'CREATE')")
    public ResponseEntity<BulkResponse<PricingResponse>> bulkCreate(
            @RequestParam UUID propertyId,
            @Valid @RequestBody BulkItemsRequest<PricingCreateRequest> request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(bulkRunner.runPayloads(request.getItems(),
                        item -> pricingService.createPricing(propertyId, item, callerId)));
    }

    @PostMapping("/bulk/pause")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PRICING', 'PAUSE')")
    public ResponseEntity<BulkResponse<PricingResponse>> bulkPause(
            @Valid @RequestBody BulkIdsRequest request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.run(request.getIds(),
                id -> pricingService.pausePricing(id, callerId)));
    }

    @PostMapping("/bulk/resume")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PRICING', 'RESUME')")
    public ResponseEntity<BulkResponse<PricingResponse>> bulkResume(
            @Valid @RequestBody BulkIdsRequest request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.run(request.getIds(),
                id -> pricingService.resumePricing(id, callerId)));
    }

    /** Delete many — each id still has to pass the "nothing depends on it" gate. */
    @DeleteMapping("/bulk")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PRICING', 'DELETE')")
    public ResponseEntity<BulkResponse<Void>> bulkDelete(@RequestParam List<UUID> ids) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.run(BulkIdsRequest.checked(ids), id -> {
            pricingService.deletePricing(id, callerId);
            return null;
        }));
    }
}
