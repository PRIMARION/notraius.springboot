package com.notraius.modules.lease.visit.visits;

import com.notraius.common.dto.bulk.BulkIdsRequest;
import com.notraius.common.dto.bulk.BulkItemsRequest;
import com.notraius.common.dto.bulk.BulkResponse;
import com.notraius.common.services.logs.BulkRunner;
import com.notraius.modules.lease.visit.visits.dto.request.visit.VisitAssessmentCreateRequest;
import com.notraius.modules.lease.visit.visits.dto.request.visit.VisitAssignRequest;
import com.notraius.modules.lease.visit.visits.dto.request.visit.VisitRequestCreateRequest;
import com.notraius.modules.lease.visit.visits.dto.request.visit.VisitRescheduleRequest;
import com.notraius.modules.lease.visit.visits.dto.request.visit.VisitResolutionRequest;
import com.notraius.modules.lease.visit.visits.dto.request.VisitTimeProposalRequest;
import com.notraius.modules.lease.visit.visits.dto.response.VisitAssessmentResponse;
import com.notraius.modules.lease.visit.visits.dto.response.VisitResponse;
import com.notraius.modules.lease.visit.visits.service.VisitService;
import jakarta.validation.Valid;
import org.primarion.module.communication.infra.ops.export.formats.ExportFormat;
import org.primarion.module.utils.utils.http.PaginatedResponse;
import org.primarion.module.utils.utils.security.security.SecurityContextUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.notraius.database.entities.lease.reference.status.Type;

/**
 * The visits sub-module (PLAN.md 5.1–5.5) — one door per act, one controller for
 * the whole tenancy of a visit.
 * <p>
 * <b>Requesting is paying.</b> There is one write endpoint and it both mints the
 * visit and funds it; there is no "create draft, then pay later" pair, because
 * a visit nobody paid for is not a visit. A refusal fails the whole call; an
 * unknown funding outcome returns 201 with the row in UNPAID, which is the
 * truthful answer and the reason the sweep exists.
 * <p>
 * The story the doors tell, in the order a well-behaved visit walks it:
 * request (charge and hold) → assign / accept / reject / decline → time / time
 * confirm → start / start confirm → assessments → complete. Two side doors run
 * alongside it: the mutual reschedule and the visitor's paid cancel, plus
 * staff's single hand on a disputed visit. The owner's forced exits are not
 * doors at all — the property's own status changes wind the visits down.
 */
@RestController
@RequestMapping("/api/v1/notraius/visits")
public class VisitController {

    private final VisitService visitService;
    private final BulkRunner bulkRunner;

    public VisitController(VisitService visitService, BulkRunner bulkRunner) {
        this.visitService = visitService;
        this.bulkRunner = bulkRunner;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Birth and reads
    // ═══════════════════════════════════════════════════════════════════════

    /** Request a visit — the act that charges the visitor and holds the money. */
    @PostMapping
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'VISITS', 'REQUEST')")
    public ResponseEntity<VisitResponse> requestVisit(@Valid @RequestBody VisitRequestCreateRequest request) {
        VisitResponse response = visitService.requestVisit(request,
                SecurityContextUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{visitId}")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'VISITS', 'GET')")
    public ResponseEntity<VisitResponse> getVisit(@PathVariable UUID visitId) {
        return ResponseEntity.ok(visitService.getVisit(visitId,
                SecurityContextUtils.getCurrentUserId()));
    }

    /**
     * The visit's receipt as a document: the funded terms, the payment reference
     * and the state the visit stands in. A party's own copy of what they agreed
     * to, in the format they ask for; its own authority pair.
     */
    @GetMapping("/{visitId}/export")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'VISITS', 'EXPORT')")
    public ResponseEntity<byte[]> exportVisit(@PathVariable UUID visitId,
                                              @RequestParam(defaultValue = "pdf") String format) {
        VisitResponse visit = visitService.getVisit(visitId,
                SecurityContextUtils.getCurrentUserId());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("visit", visit.getId());
        row.put("visitor", visit.getVisitorId());
        row.put("assignee", visit.getAssigneeId());
        row.put("owner", visit.getOwnerId());
        row.put("property", visit.getPropertyId());
        row.put("status", visit.getStatusCode());
        row.put("amount", visit.getAmount());
        row.put("currency", visit.getCurrencyId());
        row.put("payment", visit.getPaymentId());
        row.put("from", visit.getFromDate());
        row.put("to", visit.getToDate());
        row.put("startedAt", visit.getStartedAt());
        row.put("startConfirmedAt", visit.getStartConfirmedAt());
        row.put("completedAt", visit.getCompletedAt());
        ExportFormat exportFormat = ExportFormat.fromString(format);
        String filename = "visit-" + visitId;
        byte[] data = ExportFormat.getEncoder(exportFormat).encode(List.of(row), filename);
        return ResponseEntity.ok()
                .header("Content-Type", ExportFormat.getEncoder(exportFormat).getContentType())
                .header("Content-Disposition", "attachment; filename=" + filename
                        + "." + ExportFormat.getEncoder(exportFormat).getFileExtension())
                .body(data);
    }

    /**
     * The visitor's page. Defaults are the historical answer: every status,
     * newest booking first. {@code from=…} alone answers "what is still coming
     * up", because a visit counts until its own window has ended.
     */
    @GetMapping("/mine")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'VISITS', 'LIST_MINE')")
    public ResponseEntity<Page<VisitResponse>> listMyVisits(
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "recent") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PaginatedResponse.of(page, size, VisitService.sortOf(sort));
        return ResponseEntity.ok(visitService.listMyVisits(
                SecurityContextUtils.getCurrentUserId(), status, from, to, pageable));
    }

    /**
     * The counterpart's page — the visits this caller is operationally responsible
     * for, and the ones on properties they own: the owner's inbox of requests that
     * still need an assignee.
     */
    @GetMapping("/assigned")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'VISITS', 'LIST_ASSIGNED')")
    public ResponseEntity<Page<VisitResponse>> listAssignedVisits(
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "recent") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PaginatedResponse.of(page, size, VisitService.sortOf(sort));
        return ResponseEntity.ok(visitService.listAssignedVisits(
                SecurityContextUtils.getCurrentUserId(), status, from, to, pageable));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Acceptance and planning (5.2)
    // ═══════════════════════════════════════════════════════════════════════

    /** The owner stamps a human; that human then accepts or declines the duty. */
    @PostMapping("/{visitId}/assign")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'VISITS', 'ASSIGN')")
    public ResponseEntity<VisitResponse> assign(@PathVariable UUID visitId,
                                                @Valid @RequestBody VisitAssignRequest request) {
        return ResponseEntity.ok(visitService.assign(visitId, request,
                SecurityContextUtils.getCurrentUserId()));
    }

    @PostMapping("/{visitId}/accept")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'VISITS', 'ACCEPT')")
    public ResponseEntity<VisitResponse> accept(@PathVariable UUID visitId) {
        return ResponseEntity.ok(visitService.accept(visitId,
                SecurityContextUtils.getCurrentUserId()));
    }

    @PostMapping("/{visitId}/reject")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'VISITS', 'REJECT')")
    public ResponseEntity<VisitResponse> reject(@PathVariable UUID visitId) {
        return ResponseEntity.ok(visitService.reject(visitId,
                SecurityContextUtils.getCurrentUserId()));
    }

    /** The owner declines a paid request before acceptance: full refund, no fee. */
    @PostMapping("/{visitId}/decline")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'VISITS', 'DECLINE')")
    public ResponseEntity<VisitResponse> decline(@PathVariable UUID visitId) {
        return ResponseEntity.ok(visitService.decline(visitId,
                SecurityContextUtils.getCurrentUserId()));
    }

    /** Propose or counter the time — the turn rule decides which move it is. */
    @PutMapping("/{visitId}/time")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'VISITS', 'TIME_PROPOSE')")
    public ResponseEntity<VisitResponse> proposeTime(@PathVariable UUID visitId,
                                                     @Valid @RequestBody VisitTimeProposalRequest request) {
        return ResponseEntity.ok(visitService.proposeTime(visitId, request,
                SecurityContextUtils.getCurrentUserId()));
    }

    /** Confirm the counterpart's proposal: PLANNING → CONFIRMED. */
    @PostMapping("/{visitId}/time/confirm")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'VISITS', 'TIME_CONFIRM')")
    public ResponseEntity<VisitResponse> confirmTime(@PathVariable UUID visitId) {
        return ResponseEntity.ok(visitService.confirmTime(visitId,
                SecurityContextUtils.getCurrentUserId()));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Execution and outcome (5.3, 5.5)
    // ═══════════════════════════════════════════════════════════════════════

    /** The assignee marks the visit under way; the visitor confirms it. */
    @PostMapping("/{visitId}/start")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'VISITS', 'START')")
    public ResponseEntity<VisitResponse> start(@PathVariable UUID visitId) {
        return ResponseEntity.ok(visitService.start(visitId,
                SecurityContextUtils.getCurrentUserId()));
    }

    @PostMapping("/{visitId}/start/confirm")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'VISITS', 'START_CONFIRM')")
    public ResponseEntity<VisitResponse> confirmStart(@PathVariable UUID visitId) {
        return ResponseEntity.ok(visitService.confirmStart(visitId,
                SecurityContextUtils.getCurrentUserId()));
    }

    /** A checkable visit's assessment: each party states its side once. */
    @PostMapping("/{visitId}/assessments")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'VISITS', 'ASSESS')")
    public ResponseEntity<VisitAssessmentResponse> assess(
            @PathVariable UUID visitId,
            @Valid @RequestBody VisitAssessmentCreateRequest request) {
        return ResponseEntity.ok(visitService.assess(visitId, request,
                SecurityContextUtils.getCurrentUserId()));
    }

    @GetMapping("/{visitId}/assessments")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'VISITS', 'GET')")
    public ResponseEntity<List<VisitAssessmentResponse>> assessments(@PathVariable UUID visitId) {
        return ResponseEntity.ok(visitService.assessments(visitId,
                SecurityContextUtils.getCurrentUserId()));
    }

    /** The visitor confirms the visit happened: COMPLETED, custody conveyed. */
    @PostMapping("/{visitId}/complete")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'VISITS', 'COMPLETE')")
    public ResponseEntity<VisitResponse> complete(@PathVariable UUID visitId) {
        return ResponseEntity.ok(visitService.confirmComplete(visitId,
                SecurityContextUtils.getCurrentUserId()));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // The two ways out (5.4) and staff's one hand (5.5)
    // ═══════════════════════════════════════════════════════════════════════

    /** One per person per visit; at least the configured horizon before the start. */
    @PostMapping("/{visitId}/reschedule")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'VISITS', 'RESCHEDULE')")
    public ResponseEntity<VisitResponse> reschedule(@PathVariable UUID visitId,
                                                    @Valid @RequestBody VisitRescheduleRequest request) {
        return ResponseEntity.ok(visitService.reschedule(visitId, request,
                SecurityContextUtils.getCurrentUserId()));
    }

    /** The visitor's own door: free before CONFIRMED, refund plus 20% and a judgment after. */
    @PostMapping("/{visitId}/cancel")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'VISITS', 'CANCEL')")
    public ResponseEntity<VisitResponse> cancel(@PathVariable UUID visitId) {
        return ResponseEntity.ok(visitService.cancel(visitId,
                SecurityContextUtils.getCurrentUserId()));
    }

    /** REFUND returns the custody to the visitor; RELEASE conveys the split to the owner. */
    @PostMapping("/{visitId}/resolve")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'VISITS', 'RESOLVE')")
    public ResponseEntity<VisitResponse> resolve(@PathVariable UUID visitId,
                                                 @Valid @RequestBody VisitResolutionRequest request) {
        return ResponseEntity.ok(visitService.resolve(visitId, request));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Bulk doors — the same acts, N at a time
    // ═══════════════════════════════════════════════════════════════════════
    // A visitor books several windows in a row and cancels several visits at
    // once. Every door below calls the single-item service method once per id
    // in its own transaction, so the turn rule, the party rule, the refund
    // split and pay's custody release all still decide per visit — and a
    // refund that fails on one visit never drags another's money with it.

    @GetMapping("/batch")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'VISITS', 'GET')")
    public ResponseEntity<BulkResponse<VisitResponse>> batchGet(@RequestParam List<UUID> ids) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.read(BulkIdsRequest.checked(ids),
                id -> visitService.getVisit(id, callerId)));
    }

    /** Request and fund many visits in one round trip — each charges its own money. */
    @PostMapping("/bulk")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'VISITS', 'REQUEST')")
    public ResponseEntity<BulkResponse<VisitResponse>> bulkRequest(
            @Valid @RequestBody BulkItemsRequest<VisitRequestCreateRequest> request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(bulkRunner.runPayloads(request.getItems(),
                        item -> visitService.requestVisit(item, callerId)));
    }

    @PostMapping("/bulk/accept")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'VISITS', 'ACCEPT')")
    public ResponseEntity<BulkResponse<VisitResponse>> bulkAccept(
            @Valid @RequestBody BulkIdsRequest request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.run(request.getIds(),
                id -> visitService.accept(id, callerId)));
    }

    @PostMapping("/bulk/decline")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'VISITS', 'DECLINE')")
    public ResponseEntity<BulkResponse<VisitResponse>> bulkDecline(
            @Valid @RequestBody BulkIdsRequest request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.run(request.getIds(),
                id -> visitService.decline(id, callerId)));
    }

    @PostMapping("/bulk/start")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'VISITS', 'START')")
    public ResponseEntity<BulkResponse<VisitResponse>> bulkStart(
            @Valid @RequestBody BulkIdsRequest request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.run(request.getIds(),
                id -> visitService.start(id, callerId)));
    }

    @PostMapping("/bulk/complete")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'VISITS', 'COMPLETE')")
    public ResponseEntity<BulkResponse<VisitResponse>> bulkComplete(
            @Valid @RequestBody BulkIdsRequest request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.run(request.getIds(),
                id -> visitService.confirmComplete(id, callerId)));
    }

    /** Cancel many — each refund is computed against that visit's own state. */
    @PostMapping("/bulk/cancel")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'VISITS', 'CANCEL')")
    public ResponseEntity<BulkResponse<VisitResponse>> bulkCancel(
            @Valid @RequestBody BulkIdsRequest request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.run(request.getIds(),
                id -> visitService.cancel(id, callerId)));
    }
}
