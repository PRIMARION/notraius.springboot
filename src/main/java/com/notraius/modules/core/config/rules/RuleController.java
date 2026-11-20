package com.notraius.modules.core.config.rules;

import com.notraius.common.dto.bulk.BulkIdsRequest;
import com.notraius.common.dto.bulk.BulkItemsRequest;
import com.notraius.common.dto.bulk.BulkResponse;
import com.notraius.common.dto.RevisionSummaryResponse;
import com.notraius.common.services.logs.BulkRunner;
import com.notraius.config.web.PublicReadThrottle;
import com.notraius.modules.core.core.common.constants.NotraiusActivities;
import com.notraius.modules.core.config.rules.dto.request.RuleCreateRequest;
import com.notraius.modules.core.config.rules.dto.request.RuleUpdateRequest;
import com.notraius.modules.core.config.rules.dto.request.RuleVisibilityRequest;
import com.notraius.modules.core.config.rules.dto.RuleResponse;
import com.notraius.modules.core.config.rules.RuleService;
import jakarta.validation.Valid;
import org.primarion.module.utils.utils.http.PaginatedResponse;
import org.primarion.module.utils.utils.security.security.SecurityContextUtils;
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
 * The config module's rules sub-module (PLAN.md 3.1).
 * <p>
 * Writes and the catalogue page are gated at the personal tier because rules
 * are user-owned: any authenticated user creates one. The two <b>by-id reads
 * are public doors</b> — a visitor viewing a published property must see the
 * rules it commits to, and a public rule is readable by anyone; the
 * handler's {@code UNAUTHENTICATED} declaration is the gate, and each call pays an
 * IP-scoped {@link PublicReadThrottle} hit. Visibility is still scoped inside
 * the service, not by the endpoint's tier.
 * Write authority is the creator's alone — staff moderation is read authority,
 * so there is no admin write gate here to grant by accident.
 * <p>
 * <b>There is no DELETE endpoint.</b> PLAN.md 3.1 is explicit that hard
 * deletion of rules is not part of the plan: history must stay replayable, so
 * the exit is disable (reversible) or retire (checked against live selections).
 */
@RestController
@RequestMapping("/api/v1/notraius/config/rules")
public class RuleController {

    private final RuleService ruleService;
    private final BulkRunner bulkRunner;
    private final PublicReadThrottle publicReadThrottle;

    public RuleController(RuleService ruleService, BulkRunner bulkRunner,
                          PublicReadThrottle publicReadThrottle) {
        this.ruleService = ruleService;
        this.bulkRunner = bulkRunner;
        this.publicReadThrottle = publicReadThrottle;
    }

    /**
     * The rules catalogue, already scoped to what the caller may see: PUBLIC
     * rules plus their own, and everything for staff. {@code ownOnly=true} is
     * the "my rules" list; {@code visibility} and {@code status} narrow further
     * (a selection UI asks for {@code visibility=PUBLIC&status=ACTIVE}).
     */
    @GetMapping
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'RULE', 'LIST')")
    public ResponseEntity<Page<RuleResponse>> listRules(
            @RequestParam(defaultValue = "false") boolean ownOnly,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String visibility,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        // The id tie-break keeps a page boundary stable when many rules were born
        // in the same transaction (one identical createdAt each).
        Pageable pageable = PaginatedResponse.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(ruleService.listRules(callerId, ownOnly, status, visibility, search, pageable));
    }

    /**
     * Read one rule, optionally as one revision stated it.
     * <p>
     * {@code ?version=N} answers from the audit chain (the row keeps only its
     * newest truth). The two lights still decide: the creator and staff may read
     * the past, anyone else gets the same 404 a PRIVATE rule gets.
     */
    @GetMapping("/{ruleId}")
    @PreAuthorize("@authz.authority('UNAUTHENTICATED', 'GTE', 'PUBLIC', 'RULE_GET')")
    public ResponseEntity<RuleResponse> getRule(
            @PathVariable UUID ruleId,
            @RequestParam(required = false) Integer version) {
        publicReadThrottle.charge(NotraiusActivities.Rules.GET);
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(ruleService.getRule(ruleId, callerId, version));
    }

    /**
     * Which revisions this rule has had — discovery for {@code ?version=}.
     * A PRIVATE rule the caller may not see answers 404, exactly as its single
     * read does: a version list would otherwise confirm the row exists.
     */
    @GetMapping("/{ruleId}/revisions")
    @PreAuthorize("@authz.authority('UNAUTHENTICATED', 'GTE', 'PUBLIC', 'RULE_REVISIONS')")
    public ResponseEntity<List<RevisionSummaryResponse>> listRevisions(@PathVariable UUID ruleId) {
        publicReadThrottle.charge(NotraiusActivities.Rules.GET);
        return ResponseEntity.ok(ruleService.listRevisions(ruleId,
                SecurityContextUtils.getCurrentUserId()));
    }

    /** Create a rule; the body states its visibility at birth. */
    @PostMapping
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'RULE', 'CREATE')")
    public ResponseEntity<RuleResponse> createRule(@Valid @RequestBody RuleCreateRequest request) {
        UUID userId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED).body(ruleService.createRule(request, userId));
    }

    /** Edit the text — every accepted edit bumps the version and writes history. */
    @PutMapping("/{ruleId}")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'RULE', 'UPDATE')")
    public ResponseEntity<RuleResponse> updateRule(@PathVariable UUID ruleId,
                                                   @Valid @RequestBody RuleUpdateRequest request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(ruleService.updateRule(ruleId, request, callerId));
    }

    /** Flip the visibility — its own act, because it carries its own refusal. */
    @PutMapping("/{ruleId}/visibility")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'RULE', 'VISIBILITY')")
    public ResponseEntity<RuleResponse> changeVisibility(@PathVariable UUID ruleId,
                                                         @Valid @RequestBody RuleVisibilityRequest request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(ruleService.changeVisibility(ruleId, request, callerId));
    }

    /** The in-use lock: refused while a live property selection references it. */
    @PostMapping("/{ruleId}/disable")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'RULE', 'DISABLE')")
    public ResponseEntity<RuleResponse> disableRule(@PathVariable UUID ruleId) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(ruleService.disableRule(ruleId, callerId));
    }

    @PostMapping("/{ruleId}/enable")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'RULE', 'ENABLE')")
    public ResponseEntity<RuleResponse> enableRule(@PathVariable UUID ruleId) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(ruleService.enableRule(ruleId, callerId));
    }

    @PostMapping("/{ruleId}/retire")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'RULE', 'RETIRE')")
    public ResponseEntity<RuleResponse> retireRule(@PathVariable UUID ruleId) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(ruleService.retireRule(ruleId, callerId));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Bulk doors — the same acts, N at a time
    // ═══════════════════════════════════════════════════════════════════════
    // The visibility invariant does not move: a batch read answers not-found
    // for another user's PRIVATE rule exactly as the single read does, and a
    // batch write still refuses anyone but the creator. Each item runs the
    // single-item door in its own transaction, so `RULE_IN_USE` on one rule
    // never rolls back the nine that were payable.

    @GetMapping("/batch")
    @PreAuthorize("@authz.authority('UNAUTHENTICATED', 'GTE', 'PUBLIC', 'RULE_BATCH')")
    public ResponseEntity<BulkResponse<RuleResponse>> batchGet(@RequestParam List<UUID> ids) {
        publicReadThrottle.charge(NotraiusActivities.Rules.GET);
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.read(BulkIdsRequest.checked(ids),
                id -> ruleService.getRule(id, callerId)));
    }

    /** Birth many rules at once; each states its own visibility. */
    @PostMapping("/bulk")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'RULE', 'CREATE')")
    public ResponseEntity<BulkResponse<RuleResponse>> bulkCreate(
            @Valid @RequestBody BulkItemsRequest<RuleCreateRequest> request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(bulkRunner.runPayloads(request.getItems(),
                        item -> ruleService.createRule(item, callerId)));
    }

    @PostMapping("/bulk/enable")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'RULE', 'ENABLE')")
    public ResponseEntity<BulkResponse<RuleResponse>> bulkEnable(
            @Valid @RequestBody BulkIdsRequest request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.run(request.getIds(),
                id -> ruleService.enableRule(id, callerId)));
    }

    @PostMapping("/bulk/disable")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'RULE', 'DISABLE')")
    public ResponseEntity<BulkResponse<RuleResponse>> bulkDisable(
            @Valid @RequestBody BulkIdsRequest request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.run(request.getIds(),
                id -> ruleService.disableRule(id, callerId)));
    }

    @PostMapping("/bulk/retire")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'RULE', 'RETIRE')")
    public ResponseEntity<BulkResponse<RuleResponse>> bulkRetire(
            @Valid @RequestBody BulkIdsRequest request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.run(request.getIds(),
                id -> ruleService.retireRule(id, callerId)));
    }

    /** Flip many visibilities — the orphan refusal still decides per rule. */
    @PutMapping("/bulk/visibility")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'RULE', 'VISIBILITY')")
    public ResponseEntity<BulkResponse<RuleResponse>> bulkVisibility(
            @RequestParam String visibility, @Valid @RequestBody BulkIdsRequest request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        RuleVisibilityRequest body = new RuleVisibilityRequest();
        body.setVisibility(visibility);
        return ResponseEntity.ok(bulkRunner.run(request.getIds(),
                id -> ruleService.changeVisibility(id, body, callerId)));
    }
}
