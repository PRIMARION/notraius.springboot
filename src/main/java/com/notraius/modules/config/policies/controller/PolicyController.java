package com.notraius.modules.config.policies.controller;

import com.notraius.common.dto.bulk.BulkIdsRequest;
import com.notraius.common.dto.bulk.BulkItemsRequest;
import com.notraius.common.dto.bulk.BulkResponse;
import com.notraius.common.dto.responses.RevisionSummaryResponse;
import com.notraius.common.services.BulkRunner;
import com.notraius.config.web.PublicReadThrottle;
import com.notraius.modules.core.common.constants.NotraiusActivities;
import com.notraius.modules.config.policies.dto.request.PolicyCreateRequest;
import com.notraius.modules.config.policies.dto.request.PolicyMandatoryRequest;
import com.notraius.modules.config.policies.dto.request.PolicyUpdateRequest;
import com.notraius.modules.config.policies.dto.response.PolicyResponse;
import com.notraius.modules.config.policies.service.PolicyService;
import jakarta.validation.Valid;
import org.primarion.module.utils.http.PaginatedResponse;
import org.primarion.module.utils.security.SecurityContextUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * The config module's policies sub-module (PLAN.md 3.2) — admin standards.
 * <p>
 * The catalogue page is gated to the personal tier: a signed-in user browses the
 * standards to pick values against them in the asset module. The <b>by-id
 * reads are public doors</b> — a property's page links to the standard behind
 * each of its values, and a logged-out visitor must be able to follow that link,
 * so those handlers carry no {@code @PreAuthorize} at all (the servlet chain's
 * {@code PUBLIC_GET_ENDPOINTS} entry is the gate) and pay an IP-scoped
 * {@link PublicReadThrottle} hit. Every write is admin-tier, exactly like types
 * and durations.
 * <p>
 * <b>No DELETE endpoint.</b> Values already set against a policy are a
 * property's history; the exit is DISABLE (the admin's pause button), which is
 * reversible and never destroys what it paused.
 */
@RestController
@RequestMapping("/api/v1/notraius/config/policies")
public class PolicyController {

    private final PolicyService policyService;
    private final BulkRunner bulkRunner;
    private final PublicReadThrottle publicReadThrottle;

    public PolicyController(PolicyService policyService, BulkRunner bulkRunner,
                            PublicReadThrottle publicReadThrottle) {
        this.policyService = policyService;
        this.bulkRunner = bulkRunner;
        this.publicReadThrottle = publicReadThrottle;
    }

    /**
     * The catalogue, filtered by what the standard governs and where it applies:
     * {@code entity}, {@code typeId}, {@code stateId}, {@code status} and free
     * text. A selection UI asks for {@code status=ENABLED}; a disabled policy is
     * never offered as a choice.
     */
    @GetMapping
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'POLICY', 'LIST')")
    public ResponseEntity<Page<PolicyResponse>> listPolicies(
            @RequestParam(required = false) String entity,
            @RequestParam(required = false) UUID typeId,
            @RequestParam(required = false) UUID stateId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        // code is unique per catalogue but the page may span worlds; id makes the
        // order total, so a page boundary never repeats or drops a standard.
        Pageable pageable = PaginatedResponse.of(page, size,
                Sort.by(Sort.Direction.ASC, "code").and(Sort.by("id")));
        return ResponseEntity.ok(policyService.listPolicies(entity, typeId, stateId, status, search, pageable));
    }

    /**
     * Public door — no {@code @PreAuthorize}: a standard is public configuration.
     * <p>
     * {@code ?version=N} answers from the audit chain, because the row keeps only
     * its newest truth. Its history is public for the same reason the row is: a
     * property's value cites the bound behind it, and "what did the bound say
     * then" is what that citation invites.
     */
    @GetMapping("/{policyId}")
    public ResponseEntity<PolicyResponse> getPolicy(
            @PathVariable UUID policyId,
            @RequestParam(required = false) Integer version) {
        publicReadThrottle.charge(NotraiusActivities.Policies.GET);
        return ResponseEntity.ok(policyService.getPolicy(policyId, version));
    }

    /**
     * Which revisions this standard has had — discovery for {@code ?version=}.
     * Public for the same reason the standard is: a property's value cites the
     * bound behind it. Versions and instants only, never authors.
     */
    @GetMapping("/{policyId}/revisions")
    public ResponseEntity<List<RevisionSummaryResponse>> listRevisions(@PathVariable UUID policyId) {
        publicReadThrottle.charge(NotraiusActivities.Policies.GET);
        return ResponseEntity.ok(policyService.listRevisions(policyId));
    }

    @PostMapping
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'POLICY', 'CREATE')")
    public ResponseEntity<PolicyResponse> createPolicy(@Valid @RequestBody PolicyCreateRequest request) {
        UUID userId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED).body(policyService.createPolicy(request, userId));
    }

    /** Edit the text and the bounds; the version bumps and history is written. */
    @PutMapping("/{policyId}")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'POLICY', 'UPDATE')")
    public ResponseEntity<PolicyResponse> updatePolicy(@PathVariable UUID policyId,
                                                       @Valid @RequestBody PolicyUpdateRequest request) {
        return ResponseEntity.ok(policyService.updatePolicy(policyId, request));
    }

    /** The compliance switch — its own act because it backfills configurations. */
    @PutMapping("/{policyId}/mandatory")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'POLICY', 'MANDATORY')")
    public ResponseEntity<PolicyResponse> setMandatory(@PathVariable UUID policyId,
                                                       @Valid @RequestBody PolicyMandatoryRequest request) {
        return ResponseEntity.ok(policyService.setMandatory(policyId, request.getMandatory()));
    }

    /** The pause button: not retroactive destruction. */
    @PostMapping("/{policyId}/disable")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'POLICY', 'DISABLE')")
    public ResponseEntity<PolicyResponse> disablePolicy(@PathVariable UUID policyId) {
        return ResponseEntity.ok(policyService.disablePolicy(policyId));
    }

    /** Restore enforcement and re-evaluate stored values (flag, never clamp). */
    @PostMapping("/{policyId}/enable")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'POLICY', 'ENABLE')")
    public ResponseEntity<PolicyResponse> enablePolicy(@PathVariable UUID policyId) {
        return ResponseEntity.ok(policyService.enablePolicy(policyId));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Bulk doors — the admin's batch year-end, one round trip
    // ═══════════════════════════════════════════════════════════════════════
    // An admin ships standards in waves: the whole set is created, enabled or
    // disabled together. Each item still runs the single-item door in its own
    // transaction, so a code collision on one standard cannot half-apply a
    // wave, and `MANDATORY` still backfills configurations one standard at a
    // time.

    @GetMapping("/batch")
    public ResponseEntity<BulkResponse<PolicyResponse>> batchGet(@RequestParam List<UUID> ids) {
        publicReadThrottle.charge(NotraiusActivities.Policies.GET);
        return ResponseEntity.ok(bulkRunner.read(BulkIdsRequest.checked(ids), policyService::getPolicy));
    }

    @PostMapping("/bulk")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'POLICY', 'CREATE')")
    public ResponseEntity<BulkResponse<PolicyResponse>> bulkCreate(
            @Valid @RequestBody BulkItemsRequest<PolicyCreateRequest> request) {
        UUID userId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(bulkRunner.runPayloads(request.getItems(),
                        item -> policyService.createPolicy(item, userId)));
    }

    @PostMapping("/bulk/enable")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'POLICY', 'ENABLE')")
    public ResponseEntity<BulkResponse<PolicyResponse>> bulkEnable(
            @Valid @RequestBody BulkIdsRequest request) {
        return ResponseEntity.ok(bulkRunner.run(request.getIds(), policyService::enablePolicy));
    }

    @PostMapping("/bulk/disable")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'POLICY', 'DISABLE')")
    public ResponseEntity<BulkResponse<PolicyResponse>> bulkDisable(
            @Valid @RequestBody BulkIdsRequest request) {
        return ResponseEntity.ok(bulkRunner.run(request.getIds(), policyService::disablePolicy));
    }

    /** Make many standards mandatory (or release them) in one call. */
    @PutMapping("/bulk/mandatory")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'POLICY', 'MANDATORY')")
    public ResponseEntity<BulkResponse<PolicyResponse>> bulkMandatory(
            @RequestParam boolean mandatory, @Valid @RequestBody BulkIdsRequest request) {
        return ResponseEntity.ok(bulkRunner.run(request.getIds(),
                id -> policyService.setMandatory(id, mandatory)));
    }
}
