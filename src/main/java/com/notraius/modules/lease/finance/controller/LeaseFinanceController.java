package com.notraius.modules.lease.finance.controller;

import com.notraius.common.dto.bulk.BulkIdsRequest;
import com.notraius.common.dto.bulk.BulkItemsRequest;
import com.notraius.common.dto.bulk.BulkResponse;
import com.notraius.common.services.BulkRunner;
import com.notraius.modules.lease.finance.dto.request.LeaseCashConfirmRequest;
import com.notraius.modules.lease.finance.dto.request.LeaseTopUpRequest;
import com.notraius.modules.lease.finance.dto.response.LeaseCashConfirmResponse;
import com.notraius.modules.lease.finance.dto.response.LeaseInstallmentResponse;
import com.notraius.modules.lease.finance.dto.response.LeaseTopUpResponse;
import com.notraius.modules.lease.finance.service.LeaseFinanceService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The money cycle's doors (PLAN 6.3/6.8): pay what is billed, confirm the cash
 * that arrived, restore the cushion.
 * <p>
 * Deposits have no door here on purpose: escrow-bound money is pay's, and this
 * controller only touches what the calendar bills.
 */
@RestController
@RequestMapping("/api/v1/notraius/leases")
public class LeaseFinanceController {

    private final LeaseFinanceService service;
    private final BulkRunner bulkRunner;

    public LeaseFinanceController(LeaseFinanceService service, BulkRunner bulkRunner) {
        this.service = service;
        this.bulkRunner = bulkRunner;
    }

    /** The lease's calendar, deadlines first. */
    @GetMapping("/{leaseId}/installments")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'GET')")
    public ResponseEntity<Page<LeaseInstallmentResponse>> installments(
            @PathVariable UUID leaseId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PaginatedResponse.of(page, size, Sort.by(Sort.Direction.ASC, "deadline"));
        return ResponseEntity.ok(service.list(leaseId, pageable,
                SecurityContextUtils.getCurrentUserId()));
    }

    /** The tenant's direct payment to the installment's recipient. */
    @PostMapping("/installments/{breakdownId}/pay")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'INSTALLMENT_PAY')")
    public ResponseEntity<LeaseInstallmentResponse> pay(@PathVariable UUID breakdownId) {
        return ResponseEntity.ok(service.pay(breakdownId,
                SecurityContextUtils.getCurrentUserId()));
    }

    /** The receiver's word that cash arrived; a short claim never settles. */
    @PostMapping("/installments/{breakdownId}/cash")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'CASH_CONFIRM')")
    public ResponseEntity<LeaseCashConfirmResponse> confirmCash(
            @PathVariable UUID breakdownId,
            @Valid @RequestBody LeaseCashConfirmRequest request) {
        return ResponseEntity.ok(service.confirmCash(breakdownId,
                SecurityContextUtils.getCurrentUserId(), request));
    }

    /** One top-up, its own funding reference, one escrow per unit restored. */
    @PostMapping("/{leaseId}/top-ups")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'TOP_UP')")
    public ResponseEntity<LeaseTopUpResponse> topUp(@PathVariable UUID leaseId,
                                                    @Valid @RequestBody LeaseTopUpRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.topUp(leaseId,
                SecurityContextUtils.getCurrentUserId(), request));
    }

    @GetMapping("/{leaseId}/top-ups")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'GET')")
    public ResponseEntity<List<LeaseTopUpResponse>> topUps(@PathVariable UUID leaseId) {
        return ResponseEntity.ok(service.listTopUps(leaseId,
                SecurityContextUtils.getCurrentUserId()));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Bulk doors — several bills, one round trip
    // ═══════════════════════════════════════════════════════════════════════
    // Paying a month's bills is the shape this exists for. Each installment
    // still gets its own payment, its own reference and its own audit row in
    // its own transaction: a batch is not a combined charge, and one unpaid
    // item never refunds or disturbs another.

    @PostMapping("/installments/bulk/pay")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'INSTALLMENT_PAY')")
    public ResponseEntity<BulkResponse<LeaseInstallmentResponse>> bulkPay(
            @Valid @RequestBody BulkIdsRequest request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.run(request.getIds(),
                id -> service.pay(id, callerId)));
    }

    /** One top-up per payload, each minting its own funding reference. */
    @PostMapping("/{leaseId}/top-ups/bulk")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'LEASES', 'TOP_UP')")
    public ResponseEntity<BulkResponse<LeaseTopUpResponse>> bulkTopUp(
            @PathVariable UUID leaseId,
            @Valid @RequestBody BulkItemsRequest<LeaseTopUpRequest> request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(bulkRunner.runPayloads(request.getItems(),
                        item -> service.topUp(leaseId, callerId, item)));
    }
}
