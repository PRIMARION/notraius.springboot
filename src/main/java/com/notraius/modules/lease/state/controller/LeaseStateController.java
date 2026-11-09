package com.notraius.modules.lease.state.controller;

import com.notraius.common.dto.bulk.BulkIdsRequest;
import com.notraius.common.dto.bulk.BulkResponse;
import com.notraius.common.services.BulkRunner;
import com.notraius.modules.lease.state.dto.request.LeaseDisputeRequest;
import com.notraius.modules.lease.state.dto.request.LeaseRepairRequest;
import com.notraius.modules.lease.state.dto.response.LeaseNoticeResponse;
import com.notraius.modules.lease.state.dto.response.LeaseRepairResponse;
import com.notraius.modules.lease.state.dto.response.LeaseSettlementResponse;
import com.notraius.modules.lease.state.service.LeaseStateService;
import jakarta.validation.Valid;
import org.primarion.module.utils.security.SecurityContextUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The lease's immune system and its end (PLAN 6.5/6.7).
 * <p>
 * The tenant answers notices, the landlord cancels a vacation, either party ends
 * the tenancy, and the settlement's confirmation completes it. The repair
 * negotiation sits here too, because it is the move-out stage's own machinery.
 */
@RestController
@RequestMapping("/api/v1/notraius/leases")
public class LeaseStateController {

    private final LeaseStateService service;
    private final BulkRunner bulkRunner;

    public LeaseStateController(LeaseStateService service, BulkRunner bulkRunner) {
        this.service = service;
        this.bulkRunner = bulkRunner;
    }

    // ── Notices ───────────────────────────────────────────────────────────

    @GetMapping("/{leaseId}/notices")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'GET')")
    public ResponseEntity<List<LeaseNoticeResponse>> notices(@PathVariable UUID leaseId) {
        return ResponseEntity.ok(service.list(leaseId,
                SecurityContextUtils.getCurrentUserId()));
    }

    /** The tenant accepts a DEPOSIT notice: the grace window opens. */
    @PostMapping("/notices/{noticeId}/accept")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'NOTICE_ACCEPT')")
    public ResponseEntity<LeaseNoticeResponse> acceptNotice(@PathVariable UUID noticeId) {
        return ResponseEntity.ok(service.accept(noticeId,
                SecurityContextUtils.getCurrentUserId()));
    }

    /** Refusal escalates at once: the VACATION notice follows. */
    @PostMapping("/notices/{noticeId}/refuse")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'NOTICE_REFUSE')")
    public ResponseEntity<LeaseNoticeResponse> refuseNotice(@PathVariable UUID noticeId) {
        return ResponseEntity.ok(service.refuse(noticeId,
                SecurityContextUtils.getCurrentUserId()));
    }

    /** The landlord's one override: the stamp's exact reverse. */
    @PostMapping("/notices/{noticeId}/cancel")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'NOTICE_CANCEL')")
    public ResponseEntity<LeaseNoticeResponse> cancelNotice(@PathVariable UUID noticeId) {
        return ResponseEntity.ok(service.cancel(noticeId,
                SecurityContextUtils.getCurrentUserId()));
    }

    // ── End of life ───────────────────────────────────────────────────────

    /** Either party ends the tenancy: the settlement is born and the property freed. */
    @PostMapping("/{leaseId}/end")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'END')")
    public ResponseEntity<LeaseSettlementResponse> end(
            @PathVariable UUID leaseId,
            @RequestBody(required = false) LeaseDisputeRequest request) {
        return ResponseEntity.ok(service.end(leaseId,
                SecurityContextUtils.getCurrentUserId(),
                request != null ? request.getReason() : null));
    }

    @GetMapping("/{leaseId}/settlement")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'GET')")
    public ResponseEntity<LeaseSettlementResponse> settlement(@PathVariable UUID leaseId) {
        return ResponseEntity.ok(service.get(leaseId,
                SecurityContextUtils.getCurrentUserId()));
    }

    /** Everything owed is answered: the lease becomes COMPLETED. */
    @PostMapping("/settlements/{settlementId}/confirm")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'SETTLEMENT_CONFIRM')")
    public ResponseEntity<LeaseSettlementResponse> confirmSettlement(
            @PathVariable UUID settlementId) {
        return ResponseEntity.ok(service.confirm(settlementId,
                SecurityContextUtils.getCurrentUserId()));
    }

    // ── Repairs ───────────────────────────────────────────────────────────

    @PostMapping("/{leaseId}/repairs")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'REPAIR_CREATE')")
    public ResponseEntity<LeaseRepairResponse> reportRepair(
            @PathVariable UUID leaseId,
            @Valid @RequestBody LeaseRepairRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.report(leaseId,
                SecurityContextUtils.getCurrentUserId(), request));
    }

    @PostMapping("/repairs/{repairId}/confirm")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'REPAIR_CONFIRM')")
    public ResponseEntity<LeaseRepairResponse> confirmRepair(@PathVariable UUID repairId) {
        return ResponseEntity.ok(service.confirmRepair(repairId,
                SecurityContextUtils.getCurrentUserId()));
    }

    @GetMapping("/{leaseId}/repairs")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'GET')")
    public ResponseEntity<List<LeaseRepairResponse>> repairs(@PathVariable UUID leaseId) {
        return ResponseEntity.ok(service.listRepairs(leaseId,
                SecurityContextUtils.getCurrentUserId()));
    }

    // ── Disputes ──────────────────────────────────────────────────────────

    /** A ticket freezes the machine; only humans move it now. */
    @PostMapping("/{leaseId}/disputes")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'DISPUTE')")
    public ResponseEntity<Void> dispute(@PathVariable UUID leaseId,
                                        @Valid @RequestBody LeaseDisputeRequest request) {
        service.open(leaseId, SecurityContextUtils.getCurrentUserId(), request.getReason());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    /**
     * Resolution is recalculation: unfreeze, re-derive, adjudicate the lapsed
     * rows. ADMIN, because the service deliberately skips the party check —
     * an operator resolving someone else's ticket must be staff, and the
     * permission row PermissionSeeder writes is taken from THIS annotation.
     */
    @PostMapping("/{leaseId}/disputes/resolve")
    @PreAuthorize("@authz.authority('ADMIN', 'GTE', 'LEASES', 'RESOLVE')")
    public ResponseEntity<Void> resolve(@PathVariable UUID leaseId,
                                        @Valid @RequestBody LeaseDisputeRequest request) {
        service.resolve(leaseId, SecurityContextUtils.getCurrentUserId(), request.getReason());
        return ResponseEntity.noContent().build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Bulk doors — a pile of papers, one round trip
    // ═══════════════════════════════════════════════════════════════════════
    // Notices, repairs and settlements arrive in piles. Each item still walks
    // its own state machine through the single-item door, in its own
    // transaction: a notice already answered is refused alone, and one
    // settlement's outstanding balance cannot make another's confirmation
    // roll back.

    @PostMapping("/notices/bulk/accept")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'NOTICE_ACCEPT')")
    public ResponseEntity<BulkResponse<LeaseNoticeResponse>> bulkAcceptNotices(
            @Valid @RequestBody BulkIdsRequest request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.run(request.getIds(),
                id -> service.accept(id, callerId)));
    }

    @PostMapping("/notices/bulk/refuse")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'NOTICE_REFUSE')")
    public ResponseEntity<BulkResponse<LeaseNoticeResponse>> bulkRefuseNotices(
            @Valid @RequestBody BulkIdsRequest request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.run(request.getIds(),
                id -> service.refuse(id, callerId)));
    }

    @PostMapping("/notices/bulk/cancel")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'NOTICE_CANCEL')")
    public ResponseEntity<BulkResponse<LeaseNoticeResponse>> bulkCancelNotices(
            @Valid @RequestBody BulkIdsRequest request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.run(request.getIds(),
                id -> service.cancel(id, callerId)));
    }

    @PostMapping("/repairs/bulk/confirm")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'REPAIR_CONFIRM')")
    public ResponseEntity<BulkResponse<LeaseRepairResponse>> bulkConfirmRepairs(
            @Valid @RequestBody BulkIdsRequest request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.run(request.getIds(),
                id -> service.confirmRepair(id, callerId)));
    }

    @PostMapping("/settlements/bulk/confirm")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'SETTLEMENT_CONFIRM')")
    public ResponseEntity<BulkResponse<LeaseSettlementResponse>> bulkConfirmSettlements(
            @Valid @RequestBody BulkIdsRequest request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.run(request.getIds(),
                id -> service.confirm(id, callerId)));
    }
}
