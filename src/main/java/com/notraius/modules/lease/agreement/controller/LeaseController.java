package com.notraius.modules.lease.agreement.controller;

import com.notraius.common.dto.bulk.BulkIdsRequest;
import com.notraius.common.dto.bulk.BulkItemsRequest;
import com.notraius.common.dto.bulk.BulkResponse;
import com.notraius.common.services.BulkRunner;
import com.notraius.modules.lease.agreement.dto.request.LeaseCancelRequest;
import com.notraius.modules.lease.agreement.dto.request.LeaseConditionRequest;
import com.notraius.modules.lease.agreement.dto.request.LeaseCreateRequest;
import com.notraius.modules.lease.agreement.dto.request.LeaseImportRequest;
import com.notraius.modules.lease.agreement.dto.response.LeaseConditionResponse;
import com.notraius.modules.lease.agreement.dto.response.LeaseDepositResponse;
import com.notraius.modules.lease.agreement.dto.response.LeaseResponse;
import com.notraius.modules.lease.agreement.service.LeaseAgreementService;
import jakarta.validation.Valid;
import org.primarion.module.infra.export.formats.ExportFormat;
import org.primarion.module.utils.http.PaginatedResponse;
import org.primarion.module.utils.security.SecurityContextUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The lease module's agreement sub-module (PLAN 6.1/6.2/6.6) — one door per
 * concern, one controller per sub-module.
 * <p>
 * <b>Birth is funding.</b> One write endpoint mints the PENDING lease, prices
 * its deposit matrix from the policy chain and funds it in a single payment;
 * there is no "draft, then pay" pair, because a lease nobody funded is not a
 * lease. A pay refusal fails the call and rolls everything back; an unknown
 * outcome returns 201 with the lease in PENDING, which is the truthful answer
 * and the reason the reference's truth is asked again later.
 * <p>
 * After birth the owner confirms, the parties file their condition reports, and
 * the imported tenancy walks through its two verification doors. The two pages
 * keep the plan's parties apart: the tenant's own leases, and the leases born
 * from properties the caller owns.
 */
@RestController
@RequestMapping("/api/v1/notraius/leases")
public class LeaseController {

    private final LeaseAgreementService leaseService;
    private final BulkRunner bulkRunner;

    public LeaseController(LeaseAgreementService leaseService, BulkRunner bulkRunner) {
        this.leaseService = leaseService;
        this.bulkRunner = bulkRunner;
    }

    // ── Birth and the pre-commitment exit ─────────────────────────────────

    /** Sign and fund a tenancy — the act that takes the deposit matrix into custody. */
    @PostMapping
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'CREATE')")
    public ResponseEntity<LeaseResponse> create(@Valid @RequestBody LeaseCreateRequest request) {
        LeaseResponse response = leaseService.create(request,
                SecurityContextUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{leaseId}")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'GET')")
    public ResponseEntity<LeaseResponse> get(@PathVariable UUID leaseId) {
        return ResponseEntity.ok(leaseService.getLease(leaseId,
                SecurityContextUtils.getCurrentUserId()));
    }

    /**
     * The lease agreement as a document — the same figures the GET returns,
     * flattened one row per deposit so the PDF/CSV is a readable contract sheet.
     * Its own authority pair because producing a document is its own act, not a
     * second read door on the row.
     */
    @GetMapping("/{leaseId}/export")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'EXPORT')")
    public ResponseEntity<byte[]> export(@PathVariable UUID leaseId,
                                         @RequestParam(defaultValue = "pdf") String format) {
        LeaseResponse lease = leaseService.getLease(leaseId,
                SecurityContextUtils.getCurrentUserId());
        List<Map<String, Object>> rows = lease.getDeposits().isEmpty()
                ? List.of(leaseExportRow(lease, null))
                : lease.getDeposits().stream()
                        .map(deposit -> leaseExportRow(lease, deposit))
                        .toList();
        ExportFormat exportFormat = ExportFormat.fromString(format);
        String filename = "lease-" + leaseId;
        byte[] data = ExportFormat.getEncoder(exportFormat).encode(rows, filename);
        return ResponseEntity.ok()
                .header("Content-Type", ExportFormat.getEncoder(exportFormat).getContentType())
                .header("Content-Disposition", "attachment; filename=" + filename
                        + "." + ExportFormat.getEncoder(exportFormat).getFileExtension())
                .body(data);
    }

    private Map<String, Object> leaseExportRow(LeaseResponse lease,
                                               LeaseDepositResponse deposit) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("lease", lease.getId());
        row.put("tenant", lease.getTenantId());
        row.put("owner", lease.getOwnerId());
        row.put("property", lease.getPropertyId());
        row.put("status", lease.getStatus());
        row.put("rent", lease.getRentAmount());
        row.put("from", lease.getFromDate());
        row.put("to", lease.getToDate());
        row.put("matrixTotal", lease.getMatrixTotal());
        if (deposit != null) {
            row.put("policy", deposit.getPolicyCode());
            row.put("kind", deposit.getTypeCode());
            row.put("balance", deposit.getBalance());
            row.put("splits", deposit.getSplits());
            row.put("depositAmount", deposit.getAmount());
            row.put("depositStatus", deposit.getStatus());
        }
        return row;
    }

    /**
     * The tenant's page. Defaults are the historical answer — every status,
     * newest tenancy first. {@code from=…} alone answers "still running or
     * starting", because a tenancy counts until its own term has ended.
     */
    @GetMapping("/mine")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'LIST_MINE')")
    public ResponseEntity<Page<LeaseResponse>> listMine(
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "recent") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PaginatedResponse.of(page, size, LeaseAgreementService.sortOf(sort));
        return ResponseEntity.ok(leaseService.listMine(
                SecurityContextUtils.getCurrentUserId(), status, from, to, pageable));
    }

    /** The beneficiary's page — leases on the properties this caller owns. */
    @GetMapping("/owned")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'LIST_OWNED')")
    public ResponseEntity<Page<LeaseResponse>> listOwned(
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "recent") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PaginatedResponse.of(page, size, LeaseAgreementService.sortOf(sort));
        return ResponseEntity.ok(leaseService.listOwned(
                SecurityContextUtils.getCurrentUserId(), status, from, to, pageable));
    }

    /**
     * The pre-commitment exit: the whole matrix returns and the property is
     * back on the market in the same act.
     */
    @PostMapping("/{leaseId}/cancel")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'CANCEL')")
    public ResponseEntity<LeaseResponse> cancel(@PathVariable UUID leaseId,
                                                @RequestBody(required = false) LeaseCancelRequest request) {
        return ResponseEntity.ok(leaseService.cancel(leaseId,
                SecurityContextUtils.getCurrentUserId(),
                request != null ? request.getReason() : null));
    }

    // ── Confirmation ──────────────────────────────────────────────────────

    /** The owner's commitment; an unchecked pricing activates on the spot. */
    @PostMapping("/{leaseId}/confirm")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'CONFIRM')")
    public ResponseEntity<LeaseResponse> confirm(@PathVariable UUID leaseId) {
        return ResponseEntity.ok(leaseService.confirm(leaseId,
                SecurityContextUtils.getCurrentUserId()));
    }

    // ── Conditions ────────────────────────────────────────────────────────

    /** File a condition report: tenant before move-in, landlord after move-out. */
    @PostMapping("/{leaseId}/conditions")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'CONDITION_UPLOAD')")
    public ResponseEntity<LeaseConditionResponse> reportCondition(
            @PathVariable UUID leaseId,
            @Valid @RequestBody LeaseConditionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(leaseService.report(leaseId,
                SecurityContextUtils.getCurrentUserId(), request));
    }

    /** The counterpart's confirmation; the last move-in one activates the lease. */
    @PostMapping("/conditions/{conditionId}/confirm")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'CONDITION_CONFIRM')")
    public ResponseEntity<LeaseConditionResponse> confirmCondition(@PathVariable UUID conditionId) {
        return ResponseEntity.ok(leaseService.confirmCondition(conditionId,
                SecurityContextUtils.getCurrentUserId()));
    }

    @GetMapping("/{leaseId}/conditions")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'GET')")
    public ResponseEntity<List<LeaseConditionResponse>> conditions(@PathVariable UUID leaseId) {
        return ResponseEntity.ok(leaseService.list(leaseId,
                SecurityContextUtils.getCurrentUserId()));
    }

    // ── Imports ───────────────────────────────────────────────────────────

    /** The landlord declares a paper tenancy: debts, holdings, or both. */
    @PostMapping("/imports")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'IMPORT_CREATE')")
    public ResponseEntity<LeaseResponse> importLease(@Valid @RequestBody LeaseImportRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(leaseService.create(request,
                SecurityContextUtils.getCurrentUserId()));
    }

    /** The tenant verifies the import wholesale; the strict schedule begins. */
    @PostMapping("/{leaseId}/import/accept")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'IMPORT_ACCEPT')")
    public ResponseEntity<LeaseResponse> acceptImport(@PathVariable UUID leaseId) {
        return ResponseEntity.ok(leaseService.accept(leaseId,
                SecurityContextUtils.getCurrentUserId()));
    }

    /** Refusal is total: the lease dies and the funding returns to its wallet. */
    @PostMapping("/{leaseId}/import/refuse")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'IMPORT_REFUSE')")
    public ResponseEntity<LeaseResponse> refuseImport(@PathVariable UUID leaseId) {
        return ResponseEntity.ok(leaseService.refuse(leaseId,
                SecurityContextUtils.getCurrentUserId()));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Bulk doors — the same acts, N at a time
    // ═══════════════════════════════════════════════════════════════════════
    // Money is per item, so the batch is per item: every door below calls the
    // single-item service method once per id, in its own transaction. A lease
    // is funded one escrow at a time inside its own item, and one refusal
    // (not a party, wrong state, pay's veto) leaves the other items' money
    // exactly as it found it.

    @GetMapping("/batch")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'GET')")
    public ResponseEntity<BulkResponse<LeaseResponse>> batchGet(@RequestParam List<UUID> ids) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.read(BulkIdsRequest.checked(ids),
                id -> leaseService.getLease(id, callerId)));
    }

    /** Sign and fund many tenancies in one round trip. */
    @PostMapping("/bulk")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'CREATE')")
    public ResponseEntity<BulkResponse<LeaseResponse>> bulkCreate(
            @Valid @RequestBody BulkItemsRequest<LeaseCreateRequest> request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(bulkRunner.runPayloads(request.getItems(),
                        item -> leaseService.create(item, callerId)));
    }

    @PostMapping("/bulk/cancel")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'CANCEL')")
    public ResponseEntity<BulkResponse<LeaseResponse>> bulkCancel(
            @RequestParam(required = false) String reason,
            @Valid @RequestBody BulkIdsRequest request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.run(request.getIds(),
                id -> leaseService.cancel(id, callerId, reason)));
    }

    @PostMapping("/bulk/confirm")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'CONFIRM')")
    public ResponseEntity<BulkResponse<LeaseResponse>> bulkConfirm(
            @Valid @RequestBody BulkIdsRequest request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.run(request.getIds(),
                id -> leaseService.confirm(id, callerId)));
    }

    /** Confirm many condition reports — the counterpart's word, per row. */
    @PostMapping("/conditions/bulk/confirm")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'CONDITION_CONFIRM')")
    public ResponseEntity<BulkResponse<LeaseConditionResponse>> bulkConfirmConditions(
            @Valid @RequestBody BulkIdsRequest request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.run(request.getIds(),
                id -> leaseService.confirmCondition(id, callerId)));
    }

    /** The tenant verifies many imports at once — each still wholesale. */
    @PostMapping("/bulk/import/accept")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'IMPORT_ACCEPT')")
    public ResponseEntity<BulkResponse<LeaseResponse>> bulkAcceptImports(
            @Valid @RequestBody BulkIdsRequest request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.run(request.getIds(),
                id -> leaseService.accept(id, callerId)));
    }

    @PostMapping("/bulk/import/refuse")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'IMPORT_REFUSE')")
    public ResponseEntity<BulkResponse<LeaseResponse>> bulkRefuseImports(
            @Valid @RequestBody BulkIdsRequest request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.run(request.getIds(),
                id -> leaseService.refuse(id, callerId)));
    }
}
