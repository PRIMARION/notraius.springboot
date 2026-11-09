package com.notraius.modules.lease.finance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.notraius.common.audit.AuditTrail;
import com.notraius.common.NotraiusErrors;
import com.notraius.common.integration.PayPaymentClient;
import com.notraius.common.integration.ReputationAssessmentClient;
import com.notraius.common.services.StatusResolver;
import com.notraius.database.entities.asset.PropertyPolicyValue;
import com.notraius.database.entities.config.PolicyUnit;
import com.notraius.database.entities.lease.Lease;
import com.notraius.database.entities.lease.LeaseBreakdown;
import com.notraius.database.entities.lease.LeaseDeposit;
import com.notraius.database.entities.lease.LeaseDepositSplit;
import com.notraius.database.entities.lease.LeaseTopUp;
import com.notraius.database.entities.reference.Entities;
import com.notraius.database.repositories.asset.PropertyPolicyValueRepository;
import com.notraius.database.repositories.lease.LeaseBreakdownRepository;
import com.notraius.database.repositories.lease.LeaseDepositRepository;
import com.notraius.database.repositories.lease.LeaseDepositSplitRepository;
import com.notraius.database.repositories.lease.LeaseRepository;
import com.notraius.database.repositories.lease.LeaseTopUpRepository;
import com.notraius.modules.core.common.constants.NotraiusActivities;
import com.notraius.modules.core.common.constants.NotraiusStatuses;
import com.notraius.modules.lease.finance.dto.request.LeaseCashConfirmRequest;
import com.notraius.modules.lease.finance.dto.request.LeaseTopUpRequest;
import com.notraius.modules.lease.finance.dto.response.LeaseCashConfirmResponse;
import com.notraius.modules.lease.finance.dto.response.LeaseInstallmentResponse;
import com.notraius.modules.lease.finance.dto.response.LeaseTopUpResponse;
import com.notraius.modules.lease.finance.mapper.LeaseFinanceMapper;
import com.notraius.modules.lease.state.service.LeaseStateService;
import lombok.extern.slf4j.Slf4j;
import org.primarion.module.access.activity.database.entities.Activity;
import org.primarion.module.access.activity.database.repositories.ActivityRepository;
import org.primarion.module.access.consent.service.OwnershipGuard;
import org.primarion.module.errors.BadRequestException;
import org.primarion.module.errors.ConflictException;
import org.primarion.module.errors.ForbiddenException;
import org.primarion.module.errors.NotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The lease module's finance sub-module (PLAN 6.3, 6.8) in one service — one
 * door per concern, one service per sub-module.
 * <p>
 * <b>Paying is direct and exact, or it is a fact recorded.</b> A wallet payment
 * carries one direct split to the row's recipient on the bill's own reference,
 * so a retry folds into the funding that already stands; a cash claim settles
 * only when it completes the due figure. The calendar has no credit ledger and
 * no half-settled row.
 * <p>
 * <b>Consumption is a law, not a favour.</b> A lapsed installment draws its
 * oldest HELD advance escrow whole — never a part of it — and the counter
 * follows the hull of the policy's own unit.
 * <p>
 * <b>One miss, one act, one judgment.</b> The verdict marks the row OVERDUE, files
 * the all-axes assessment inside the same transaction, then lets custody cover
 * the miss if it can; a DISPUTED tenancy freezes all three, and an imported
 * backlog's first skip goes straight to VACATION.
 * <p>
 * <b>Restoring is its own funding event.</b> Each top-up is saved PENDING before
 * the call leaves, one escrow per unit restored, so an unanswered ask repeats
 * and is never compensated blind.
 */
@Service
@Slf4j
public class LeaseFinanceService extends OwnershipGuard {

    private final LeaseBreakdownRepository breakdownRepository;
    private final LeaseDepositRepository depositRepository;
    private final LeaseDepositSplitRepository splitRepository;
    private final LeaseRepository leaseRepository;
    private final LeaseTopUpRepository topUpRepository;
    private final PropertyPolicyValueRepository valueRepository;
    private final PayPaymentClient payPaymentClient;
    private final ReputationAssessmentClient assessmentClient;
    private final ActivityRepository activityRepository;
    private final StatusResolver statusResolver;
    private final LeaseStateService stateService;
    private final LeaseFinanceMapper financeMapper;
    private final AuditTrail auditTrail;

    private static final String BILL_REFERENCE_PREFIX = "LEASE-BILL:";
    private static final String SPLIT_REFERENCE_PREFIX = "LEASE-TOPUP-SPLIT:";

    public LeaseFinanceService(
            LeaseBreakdownRepository breakdownRepository,
            LeaseDepositRepository depositRepository,
            LeaseDepositSplitRepository splitRepository,
            LeaseRepository leaseRepository,
            LeaseTopUpRepository topUpRepository,
            PropertyPolicyValueRepository valueRepository,
            PayPaymentClient payPaymentClient,
            ReputationAssessmentClient assessmentClient,
            ActivityRepository activityRepository,
            StatusResolver statusResolver,
            LeaseStateService stateService,
            LeaseFinanceMapper financeMapper,
            AuditTrail auditTrail) {
        this.breakdownRepository = breakdownRepository;
        this.depositRepository = depositRepository;
        this.splitRepository = splitRepository;
        this.leaseRepository = leaseRepository;
        this.topUpRepository = topUpRepository;
        this.valueRepository = valueRepository;
        this.payPaymentClient = payPaymentClient;
        this.assessmentClient = assessmentClient;
        this.activityRepository = activityRepository;
        this.statusResolver = statusResolver;
        this.stateService = stateService;
        this.financeMapper = financeMapper;
        this.auditTrail = auditTrail;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // The overdue miss settled from custody
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Settle an unpaid installment from the advance's custody if it can.
     *
     * @return true when the row became DEPOSITED
     */
    @Transactional
    public boolean consume(UUID breakdownId) {
        Optional<LeaseBreakdown> found = breakdownRepository.lockById(breakdownId);
        if (found.isEmpty()) {
            return false;
        }
        LeaseBreakdown bill = found.get();
        String status = bill.getStatus() != null ? bill.getStatus().getCode() : null;
        if (!NotraiusStatuses.LEASE_BREAKDOWN_PENDING.equals(status)
                && !NotraiusStatuses.LEASE_BREAKDOWN_OVERDUE.equals(status)) {
            // The tenant's own payment won the race; nothing is left to consume.
            return false;
        }
        if (!NotraiusStatuses.LEASE_BREAKDOWN_INSTALLMENT.equals(
                bill.getType() != null ? bill.getType().getCode() : null)) {
            // Only rent is consumed; a repair or settlement debt is paid, never
            // drawn from the cushion.
            return false;
        }

        Lease lease = bill.getLease();
        if (NotraiusStatuses.LEASE_DISPUTED.equalsIgnoreCase(
                lease.getStatus() != null ? lease.getStatus().getCode() : null)) {
            return false;
        }
        LeaseDeposit advance = advanceOf(lease.getId());
        if (advance == null || balanceOf(advance).compareTo(BigDecimal.ONE) < 0) {
            return false;
        }

        List<LeaseDepositSplit> held = splitRepository.findByLeaseIdAndStatusCodeOrderBySequenceAsc(
                lease.getId(), NotraiusStatuses.LEASE_SPLIT_HELD).stream()
                .filter(split -> split.getDeposit() != null
                        && split.getDeposit().getId().equals(advance.getId()))
                .toList();
        if (held.isEmpty()) {
            // No escrow left in this deposit even though the counter says one is
            // owed: fail closed rather than invent custody.
            log.warn("Lease {} advance counters {} but holds no HELD split; nothing to consume",
                    lease.getId(), advance.getBalance());
            return false;
        }

        LeaseDepositSplit split = held.get(0);
        try {
            payPaymentClient.releaseSplit(split.getPaymentId(), split.getSplitId());
        } catch (RuntimeException e) {
            // Fail closed: custody stays HELD, the row is overdue, a human
            // reconciles the destination and the vigil retries later (6.3).
            markOverdue(bill, "release refused: " + e.getMessage());
            return false;
        }

        split.setStatus(statusResolver.resolve(Entities.LEASE_DEPOSIT_SPLITS,
                NotraiusStatuses.LEASE_SPLIT_RELEASED));
        splitRepository.save(split);

        boolean amountHull = advance.getPolicy() != null
                && advance.getPolicy().getUnit() == PolicyUnit.AMOUNT;
        // balance is a unit-equivalent counter, not money: one UNITS split is
        // one unit, while one AMOUNT global split covers the frozen
        // month-equivalent stored as the deposit's standard. Zeroing it on
        // every AMOUNT release lost the months of any additional global split
        // a top-up had funded, so later consumptions saw an empty cushion
        // while HELD custody still existed.
        BigDecimal released = amountHull ? standardOf(advance) : BigDecimal.ONE;
        advance.setBalance(balanceOf(advance).subtract(released).max(BigDecimal.ZERO));
        advance.setVersion(advance.getVersion() + 1);
        depositRepository.save(advance);

        bill.setStatus(statusResolver.resolve(Entities.LEASE_BREAKDOWNS,
                NotraiusStatuses.LEASE_BREAKDOWN_DEPOSITED));
        bill.setSettledAt(Instant.now());
        bill.setEscrowPaymentId(split.getPaymentId());
        bill.setEscrowSplitId(split.getSplitId());
        bill.setVersion(bill.getVersion() + 1);
        breakdownRepository.save(bill);

        auditConsumption(NotraiusActivities.Leases.CONSUME, bill, Map.of(
                "breakdownId", bill.getId().toString(),
                "splitId", split.getSplitId().toString(),
                "paymentId", split.getPaymentId().toString(),
                "advanceBalance", advance.getBalance().toString()));
        log.info("Lease {} bill {} consumed from advance split {} (payment {}); balance now {}",
                lease.getId(), bill.getId(), split.getSplitId(), split.getPaymentId(), advance.getBalance());

        // The cushion may now be critically thin: the notice engine reads it in
        // the same transaction (6.5).
        stateService.evaluateDeposit(lease);
        return true;
    }

    private void markOverdue(LeaseBreakdown bill, String reason) {
        bill.setStatus(statusResolver.resolve(Entities.LEASE_BREAKDOWNS,
                NotraiusStatuses.LEASE_BREAKDOWN_OVERDUE));
        bill.setVersion(bill.getVersion() + 1);
        breakdownRepository.save(bill);
        auditConsumption(NotraiusActivities.Leases.OVERDUE, bill, Map.of(
                "breakdownId", bill.getId().toString(),
                "reason", reason));
        log.warn("Lease {} bill {} left OVERDUE: {}", bill.getLease().getId(), bill.getId(), reason);
    }

    private void auditConsumption(String activityCode, LeaseBreakdown bill, Map<String, String> data) {
        auditTrail.record(Entities.LEASE_BREAKDOWNS, activityCode, bill.getId(),
                activityCode.substring(activityCode.indexOf('.') + 1), null, data);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // The miss's judgment
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * File the miss's judgment if it has not been filed. Called inside the
     * resolution transaction, so a failure rolls the whole resolution back and
     * the vigil retries the row on its next pass.
     *
     * @return true when this call wrote the judgment
     */
    @Transactional
    public boolean file(LeaseBreakdown bill) {
        if (bill.isAssessmentFiled()) {
            return false;
        }
        if (!NotraiusStatuses.LEASE_BREAKDOWN_INSTALLMENT.equals(
                bill.getType() != null ? bill.getType().getCode() : null)) {
            return false;
        }
        UUID target = bill.getLease() != null ? bill.getLease().getUserId() : null;
        UUID author = bill.getUserId() != null ? bill.getUserId()
                : bill.getLease() != null ? bill.getLease().getOwnerId() : null;
        if (target == null || author == null || target.equals(author)) {
            log.warn("Breakdown {} has no judgeable pair (target={}, author={})",
                    bill.getId(), target, author);
            return false;
        }

        UUID activityId = activityRepository.findByCode(NotraiusActivities.Leases.ASSESS)
                .map(Activity::getId)
                .orElseThrow(() -> new IllegalStateException(
                        "Activity not seeded: " + NotraiusActivities.Leases.ASSESS));
        String reason = "Installment " + (bill.getPeriodIndex() != null ? bill.getPeriodIndex() : bill.getId())
                + " of lease " + bill.getLease().getId() + " passed its deadline unpaid";

        Optional<UUID> created = assessmentClient.fileAllAxesNegativeForEntity(
                target, author, statusResolver.resolveEntity(Entities.LEASE_BREAKDOWNS).getId(),
                bill.getId(), NotraiusActivities.Leases.ASSESS, activityId, reason);

        bill.setAssessmentFiled(true);
        bill.setVersion(bill.getVersion() + 1);
        breakdownRepository.save(bill);
        auditAssessment(bill, created.orElse(null), reason);
        log.info("Breakdown {} judged automatically (assessment {}); target={} author={}",
                bill.getId(), created.orElse(null), target, author);
        return true;
    }

    private void auditAssessment(LeaseBreakdown bill, UUID assessmentId, String reason) {
        auditTrail.record(Entities.LEASE_BREAKDOWNS, NotraiusActivities.Leases.ASSESS,
                bill.getId(), "ASSESS", null, Map.of(
                        "breakdownId", bill.getId().toString(),
                        "assessmentId", assessmentId != null ? assessmentId.toString() : "pending",
                        "reason", reason));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // The deadline's verdict
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Judge and resolve one lapsed bill.
     *
     * @return true when this call changed the row
     */
    @Transactional
    public boolean resolve(UUID breakdownId) {
        Optional<LeaseBreakdown> found = breakdownRepository.lockById(breakdownId);
        if (found.isEmpty()) {
            return false;
        }
        LeaseBreakdown bill = found.get();
        String status = bill.getStatus() != null ? bill.getStatus().getCode() : null;
        if (!NotraiusStatuses.LEASE_BREAKDOWN_PENDING.equals(status)) {
            return false;
        }
        Instant now = Instant.now();
        if (bill.getDeadline() == null || bill.getDeadline().isAfter(now)) {
            return false;
        }
        Lease lease = bill.getLease();
        if (NotraiusStatuses.LEASE_DISPUTED.equalsIgnoreCase(
                lease.getStatus() != null ? lease.getStatus().getCode() : null)) {
            return false;
        }

        bill.setStatus(statusResolver.resolve(Entities.LEASE_BREAKDOWNS,
                NotraiusStatuses.LEASE_BREAKDOWN_OVERDUE));
        bill.setVersion(bill.getVersion() + 1);
        breakdownRepository.save(bill);

        boolean assessed = file(bill);
        boolean consumed = consume(bill.getId());

        if (NotraiusStatuses.LEASE_ORIGIN_IMPORT.equalsIgnoreCase(lease.getOrigin())
                && hasReconversion(lease)) {
            // No intermediate grace on an imported backlog (6.6): one skip is
            // the VACATION notice's direct trigger.
            stateService.issueVacation(lease,
                    "An imported lease skipped installment " + bill.getPeriodIndex());
        }

        log.info("Lease {} bill {} lapsed: overdue, assessed={}, consumed={}",
                lease.getId(), bill.getId(), assessed, consumed);
        return true;
    }

    /** Resolution's recalculation: every lapsed miss decided from the record now. */
    @Transactional
    public int recalc(UUID leaseId) {
        List<LeaseBreakdown> lapsed = breakdownRepository.findByLeaseIdAndStatusCodes(
                leaseId, List.of(NotraiusStatuses.LEASE_BREAKDOWN_PENDING)).stream()
                .filter(bill -> bill.getDeadline() != null && !bill.getDeadline().isAfter(Instant.now()))
                .toList();
        int acted = 0;
        for (LeaseBreakdown bill : lapsed) {
            if (resolve(bill.getId())) {
                acted++;
            }
        }
        return acted;
    }

    private boolean hasReconversion(Lease lease) {
        return stateService.hasOpenNotice(lease.getId(), NotraiusStatuses.LEASE_NOTICE_RECONVERSION);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Paying what the calendar bills
    // ═══════════════════════════════════════════════════════════════════════

    /** The tenant pays an installment with a direct payment to its recipient. */
    @Transactional
    public LeaseInstallmentResponse pay(UUID breakdownId, UUID callerId) {
        LeaseBreakdown bill = requireLocked(breakdownId);
        Lease lease = bill.getLease();
        requireUnanswered(bill);
        String type = typeCode(bill);

        UUID payee;
        if (NotraiusStatuses.LEASE_BREAKDOWN_INSTALLMENT.equals(type)) {
            if (callerId == null || !callerId.equals(lease.getUserId())) {
                throw new ForbiddenException(NotraiusErrors.LEASE_BREAKDOWN_PAYER_FORBIDDEN,
                        "Only the tenant may pay lease " + lease.getId() + "'s installments");
            }
            payee = bill.getUserId();
        } else {
            // A settlement/repair debt names its payer on the row; the payee is
            // the landlord-side counterpart, which is not the row's author.
            if (callerId == null || !callerId.equals(bill.getUserId())) {
                throw new ForbiddenException(NotraiusErrors.LEASE_BREAKDOWN_PAYER_FORBIDDEN,
                        "Breakdown " + breakdownId + " names " + bill.getUserId() + " as its payer");
            }
            payee = lease.getOwnerId();
        }
        if (payee == null) {
            throw new ConflictException(NotraiusErrors.LEASE_STATE_INVALID,
                    "Breakdown " + breakdownId + " has no recipient, so its money has nowhere to go");
        }
        UUID currencyId = lease.getPricing().getProperty() != null
                ? lease.getPricing().getProperty().getCurrencyId() : null;
        if (currencyId == null) {
            throw new ConflictException(NotraiusErrors.LEASE_CURRENCY_UNRESOLVED,
                    "Lease " + lease.getId() + "'s property has no currency");
        }

        PayPaymentClient.PaymentRef payment;
        try {
            payment = payPaymentClient.createDirectPayment(callerId, currencyId, bill.getAmount(),
                    NotraiusActivities.Leases.INSTALLMENT_PAY, reference(bill.getId()), payee, reason(bill));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                throw new ConflictException(NotraiusErrors.LEASE_PAYMENT_REFUSED,
                        "The payment was refused (" + e.getStatusCode().value() + "), so the bill stands unpaid");
            }
            throw new ConflictException(NotraiusErrors.LEASE_FUNDING_UNAVAILABLE,
                    "The payment service did not answer; the bill stands unpaid and the retry finds its funding");
        } catch (RuntimeException e) {
            throw new ConflictException(NotraiusErrors.LEASE_FUNDING_UNAVAILABLE,
                    "The payment service did not answer; the bill stands unpaid and the retry finds its funding");
        }

        if (payment.amount() != null && payment.amount().compareTo(bill.getAmount()) != 0) {
            throw new ConflictException(NotraiusErrors.LEASE_BREAKDOWN_AMOUNT_MISMATCH,
                    "Pay answered " + payment.amount() + " for a bill due " + bill.getAmount());
        }

        settle(bill, Instant.now(), null, null);
        auditPayment(NotraiusActivities.Leases.INSTALLMENT_PAY, bill, "PAY", financeMapper.toResponse(bill));
        log.info("Lease {} bill {} paid by {} (payment {})",
                lease.getId(), bill.getId(), callerId, payment.id());
        return financeMapper.toResponse(bill);
    }

    /**
     * The receiver's cash confirmation. Only a claim that completes the due
     * figure settles the row; a shortfall is a recorded fact, not a paid bill.
     */
    @Transactional
    public LeaseCashConfirmResponse confirmCash(UUID breakdownId, UUID callerId,
                                                LeaseCashConfirmRequest request) {
        LeaseBreakdown bill = requireLocked(breakdownId);
        Lease lease = bill.getLease();
        requireUnanswered(bill);

        UUID receiver = NotraiusStatuses.LEASE_BREAKDOWN_INSTALLMENT.equals(typeCode(bill))
                ? bill.getUserId() : lease.getOwnerId();
        if (callerId == null || !callerId.equals(receiver)) {
            throw new ForbiddenException(NotraiusErrors.LEASE_PARTY_FORBIDDEN,
                    "Only the bill's recipient may confirm the cash it received");
        }

        BigDecimal claimed = request.getAmount();
        BigDecimal due = bill.getAmount();
        if (claimed.compareTo(due) > 0) {
            throw new BadRequestException(NotraiusErrors.LEASE_BREAKDOWN_AMOUNT_MISMATCH,
                    "The cash claim " + claimed + " exceeds the due figure " + due
                            + "; the calendar has no credit ledger");
        }
        boolean settled = claimed.compareTo(due) == 0;
        if (settled) {
            settle(bill, Instant.now(), null, null);
        }

        LeaseCashConfirmResponse response = new LeaseCashConfirmResponse();
        response.setBreakdownId(bill.getId());
        response.setLeaseId(lease.getId());
        response.setStatus(bill.getStatus() != null ? bill.getStatus().getCode() : null);
        response.setAmountDue(due);
        response.setAmountClaimed(claimed);
        response.setOutstanding(settled ? BigDecimal.ZERO : due.subtract(claimed));
        response.setSettled(settled);
        response.setSettledAt(bill.getSettledAt());

        auditPayment(NotraiusActivities.Leases.CASH_CONFIRM, bill,
                settled ? "CASH_SETTLED" : "CASH_SHORT", response);
        log.info("Lease {} bill {} cash-confirmed by {}: claimed {} of {} ({})",
                lease.getId(), bill.getId(), callerId, claimed, due, settled ? "settled" : "short");
        return response;
    }

    @Transactional(readOnly = true)
    public Page<LeaseInstallmentResponse> list(UUID leaseId, Pageable pageable, UUID callerId) {
        Lease lease = leaseRepository.findById(leaseId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.LEASE_NOT_FOUND,
                        "Lease not found: " + leaseId));
        requireParty("Only a party to lease " + leaseId + " may read its calendar", lease, callerId);
        return breakdownRepository.findByLeaseId(leaseId, pageable).map(financeMapper::toResponse);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // OwnershipGuard — the parties to the lease
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * args[0]: the lease, args[1]: the caller id. The tenant and the landlord
     * are the two parties; a money recipient is not one (6.9).
     */
    @Override
    public boolean canAccess(Object... args) {
        if (args.length < 2 || !(args[0] instanceof Lease lease)
                || !(args[1] instanceof UUID callerId)) {
            return false;
        }
        return callerId != null
                && (callerId.equals(lease.getUserId()) || callerId.equals(lease.getOwnerId()));
    }

    /**
     * A party-level door: the tenant and the landlord pass directly, a seat or
     * app delegation passes through the estate's ownership guard, and everyone
     * else is refused with the module's own error code.
     */
    private void requireParty(String message, Lease lease, UUID callerId) {
        if (callerId != null
                && (callerId.equals(lease.getUserId()) || callerId.equals(lease.getOwnerId()))) {
            return;
        }
        try {
            requireOwnership(message, lease, callerId);
        } catch (ForbiddenException e) {
            throw new ForbiddenException(NotraiusErrors.LEASE_PARTY_FORBIDDEN, message);
        }
    }

    private void settle(LeaseBreakdown bill, Instant now, UUID escrowPaymentId, UUID escrowSplitId) {
        bill.setStatus(statusResolver.resolve(Entities.LEASE_BREAKDOWNS,
                NotraiusStatuses.LEASE_BREAKDOWN_SETTLED));
        bill.setSettledAt(now);
        bill.setEscrowPaymentId(escrowPaymentId);
        bill.setEscrowSplitId(escrowSplitId);
        bill.setVersion(bill.getVersion() + 1);
        breakdownRepository.save(bill);
    }

    private LeaseBreakdown requireLocked(UUID breakdownId) {
        return breakdownRepository.lockById(breakdownId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.LEASE_BREAKDOWN_NOT_FOUND,
                        "Lease breakdown not found: " + breakdownId));
    }

    private void requireUnanswered(LeaseBreakdown bill) {
        String status = bill.getStatus() != null ? bill.getStatus().getCode() : null;
        if (!NotraiusStatuses.LEASE_BREAKDOWN_PENDING.equals(status)
                && !NotraiusStatuses.LEASE_BREAKDOWN_OVERDUE.equals(status)) {
            throw new ConflictException(NotraiusErrors.LEASE_BREAKDOWN_ALREADY_RESOLVED,
                    "Breakdown " + bill.getId() + " is " + status + "; the ledger never rewrites it");
        }
    }

    private String typeCode(LeaseBreakdown bill) {
        return bill.getType() != null ? bill.getType().getCode() : null;
    }

    private UUID reference(UUID breakdownId) {
        return UUID.nameUUIDFromBytes((BILL_REFERENCE_PREFIX + breakdownId).getBytes(StandardCharsets.UTF_8));
    }

    private String reason(LeaseBreakdown bill) {
        Integer period = bill.getPeriodIndex();
        return (period != null ? "Installment " + period : bill.getTitle())
                + " — lease " + bill.getLease().getId();
    }

    private void auditPayment(String activityCode, LeaseBreakdown bill, String operation, Object after) {
        auditTrail.record(Entities.LEASE_BREAKDOWNS, activityCode, bill.getId(), operation, null, after);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Restoring the advance cushion
    // ═══════════════════════════════════════════════════════════════════════

    /** Fund the restoration: the tenant's wallet into fresh HELD escrows. */
    @Transactional
    public LeaseTopUpResponse topUp(UUID leaseId, UUID callerId, LeaseTopUpRequest request) {
        Lease lease = leaseRepository.findById(leaseId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.LEASE_NOT_FOUND,
                        "Lease not found: " + leaseId));
        if (callerId == null || !callerId.equals(lease.getUserId())) {
            throw new ForbiddenException(NotraiusErrors.LEASE_TOPUP_FORBIDDEN,
                    "Only the tenant may restore the advance cushion");
        }
        if (!NotraiusStatuses.LEASE_ACTIVE.equalsIgnoreCase(
                lease.getStatus() != null ? lease.getStatus().getCode() : null)) {
            throw new ConflictException(NotraiusErrors.LEASE_STATE_INVALID,
                    "Lease " + leaseId + " is not ACTIVE; a cushion is restored on a live tenancy");
        }

        LeaseDeposit advance = advanceOf(leaseId);
        if (advance == null) {
            throw new ConflictException(NotraiusErrors.LEASE_TOPUP_INVALID,
                    "Lease " + leaseId + " holds no ADVANCE deposit; there is no cushion to restore");
        }
        boolean amountHull = advance.getPolicy() != null
                && advance.getPolicy().getUnit() == PolicyUnit.AMOUNT;
        int units = request.getUnits();
        if (units <= 0 || (amountHull && units != 1)) {
            throw new ConflictException(NotraiusErrors.LEASE_TOPUP_INVALID,
                    amountHull
                            ? "An AMOUNT-valued advance restores exactly one global split"
                            : "A top-up restores a whole, positive number of units");
        }
        BigDecimal perUnit = amountHull ? amountValue(lease, advance) : perUnitRent(lease);
        BigDecimal amount = perUnit.multiply(BigDecimal.valueOf(units));

        UUID currencyId = lease.getPricing().getProperty() != null
                ? lease.getPricing().getProperty().getCurrencyId() : null;
        if (currencyId == null) {
            throw new ConflictException(NotraiusErrors.LEASE_CURRENCY_UNRESOLVED,
                    "Lease " + leaseId + "'s property has no currency");
        }

        LeaseTopUp topUp = new LeaseTopUp();
        topUp.setLease(lease);
        topUp.setDeposit(advance);
        topUp.setAmount(amount);
        topUp.setUnits(units);
        topUp.setStatus(statusResolver.resolve(Entities.LEASE_TOPUPS,
                NotraiusStatuses.LEASE_TOPUP_PENDING));
        topUp.setVersion(1);
        topUp.setCreatedBy(callerId);
        // Persisted BEFORE the call leaves: the reference must exist whatever the
        // call does (6.3's funding discipline).
        topUpRepository.save(topUp);

        List<PayPaymentClient.MatrixSplit> wire = matrix(lease, topUp, perUnit, units);
        PayPaymentClient.PaymentRef payment;
        try {
            payment = payPaymentClient.createCustodialMatrix(callerId, currencyId,
                    NotraiusActivities.Leases.TOP_UP, topUp.getId(), wire);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                fail(topUp, "pay refused the top-up (" + e.getStatusCode().value() + ")");
                return financeMapper.toResponse(topUp);
            }
            log.warn("Top-up {} outcome unknown ({}); left PENDING for reconciliation",
                    topUp.getId(), e.getStatusCode());
            return financeMapper.toResponse(topUp);
        } catch (RuntimeException e) {
            log.warn("Top-up {} outcome unknown ({}); left PENDING for reconciliation",
                    topUp.getId(), e.getMessage());
            return financeMapper.toResponse(topUp);
        }

        List<PayPaymentClient.SplitInfo> born = payment.splits() != null ? payment.splits() : List.of();
        if (born.size() != wire.size()) {
            // Money moved but cannot be explained by this side's ledger: leave
            // the row PENDING for a human/pay reconcile, never invent splits.
            topUp.setPaymentId(payment.id());
            topUp.setVersion(topUp.getVersion() + 1);
            topUpRepository.save(topUp);
            log.warn("Top-up {} funding {} returned {} splits for {} units; left PENDING",
                    topUp.getId(), payment.id(), born.size(), wire.size());
            return financeMapper.toResponse(topUp);
        }

        int sequence = nextSequence(leaseId);
        for (int index = 0; index < wire.size(); index++) {
            PayPaymentClient.SplitInfo split = born.get(index);
            PayPaymentClient.MatrixSplit addressed = wire.get(index);
            LeaseDepositSplit row = new LeaseDepositSplit();
            row.setLease(lease);
            row.setDeposit(advance);
            row.setPaymentId(payment.id());
            row.setSplitId(split.splitId());
            row.setReferenceId(addressed.referenceId());
            row.setAmount(split.amount() != null ? split.amount() : addressed.amount());
            row.setSequence(sequence + index);
            row.setStatus(statusResolver.resolve(Entities.LEASE_DEPOSIT_SPLITS,
                    NotraiusStatuses.LEASE_SPLIT_HELD));
            splitRepository.save(row);
        }

        // Same counter law at the other end: restoring one AMOUNT global split
        // restores the whole months it covers (the frozen standard), not a
        // single month.
        BigDecimal restored = amountHull
                ? standardOf(advance)
                : BigDecimal.valueOf(units);
        advance.setBalance(balanceOf(advance).add(restored));
        advance.setVersion(advance.getVersion() + 1);
        depositRepository.save(advance);

        topUp.setPaymentId(payment.id());
        topUp.setStatus(statusResolver.resolve(Entities.LEASE_TOPUPS,
                NotraiusStatuses.LEASE_TOPUP_FUNDED));
        topUp.setVersion(topUp.getVersion() + 1);
        topUpRepository.save(topUp);

        auditTopUp(topUp, Map.of(
                "topUpId", topUp.getId().toString(),
                "paymentId", payment.id().toString(),
                "units", String.valueOf(units),
                "amount", amount.toString(),
                "advanceBalance", advance.getBalance().toString()));
        log.info("Lease {} top-up {} funded: {} unit(s) for {} (payment {})",
                leaseId, topUp.getId(), units, amount, payment.id());

        // The demand is met the instant the counter reaches its standard (6.5).
        stateService.evaluateDeposit(lease);
        return financeMapper.toResponse(topUp);
    }

    @Transactional(readOnly = true)
    public List<LeaseTopUpResponse> listTopUps(UUID leaseId, UUID callerId) {
        Lease lease = leaseRepository.findById(leaseId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.LEASE_NOT_FOUND,
                        "Lease not found: " + leaseId));
        requireParty("Only a party to lease " + leaseId + " may read its top-ups", lease, callerId);
        return topUpRepository.findByLeaseIdOrderByCreatedAtDesc(leaseId).stream()
                .map(financeMapper::toResponse)
                .toList();
    }

    private List<PayPaymentClient.MatrixSplit> matrix(Lease lease, LeaseTopUp topUp,
                                                      BigDecimal perUnit, int units) {
        UUID owner = lease.getPricing().getProperty() != null
                && lease.getPricing().getProperty().getOwnerId() != null
                ? lease.getPricing().getProperty().getOwnerId() : lease.getOwnerId();
        UUID entityId = statusResolver.resolveEntity(Entities.LEASES).getId();
        List<PayPaymentClient.MatrixSplit> splits = new ArrayList<>();
        for (int unit = 1; unit <= units; unit++) {
            splits.add(new PayPaymentClient.MatrixSplit(owner, perUnit, entityId,
                    splitReference(topUp.getId(), unit),
                    "Advance top-up " + unit + "/" + units + " — lease " + lease.getId()));
        }
        return splits;
    }

    static UUID splitReference(UUID topUpId, int unit) {
        return UUID.nameUUIDFromBytes(
                (SPLIT_REFERENCE_PREFIX + topUpId + ":" + unit).getBytes(StandardCharsets.UTF_8));
    }

    private BigDecimal amountValue(Lease lease, LeaseDeposit advance) {
        PropertyPolicyValue value = valueRepository
                .findByPricingIdAndPolicyId(lease.getPricing().getId(), advance.getPolicy().getId())
                .orElseThrow(() -> new ConflictException(NotraiusErrors.LEASE_TOPUP_INVALID,
                        "Pricing " + lease.getPricing().getId()
                                + " carries no value for the AMOUNT standard '" + advance.getPolicy().getCode() + "'"));
        if (value.getValue() == null || value.getValue().signum() <= 0) {
            throw new ConflictException(NotraiusErrors.LEASE_TOPUP_INVALID,
                    "The AMOUNT standard '" + advance.getPolicy().getCode() + "' values "
                            + value.getValue() + ", which cannot fund custody");
        }
        return value.getValue();
    }

    /**
     * The frozen rent, and never the live pricing's: a top-up prices a unit of
     * the contract the tenant signed. The former fallback to
     * {@code pricing.getAmount()} answered today's offer for a past tenancy —
     * exactly the wrong-version read the freeze exists to stop — so a missing
     * term now refuses instead of quietly repricing.
     */
    private BigDecimal perUnitRent(Lease lease) {
        BigDecimal rent = lease.getRentAmount();
        if (rent == null || rent.signum() <= 0) {
            throw new ConflictException(NotraiusErrors.LEASE_TOPUP_INVALID,
                    "Lease " + lease.getId() + " has no positive frozen rent to price a unit");
        }
        return rent;
    }

    private LeaseDeposit advanceOf(UUID leaseId) {
        for (LeaseDeposit deposit : depositRepository.findByLeaseIdOrderByPolicyCodeAsc(leaseId)) {
            if (deposit.getType() != null
                    && NotraiusStatuses.LEASE_DEPOSIT_ADVANCE.equals(deposit.getType().getCode())) {
                return deposit;
            }
        }
        return null;
    }

    private BigDecimal balanceOf(LeaseDeposit deposit) {
        return deposit.getBalance() != null ? deposit.getBalance() : BigDecimal.ZERO;
    }

    /**
     * The deposit's frozen unit-equivalent standard — the counter one full
     * AMOUNT global split covers (birth's {@code value ÷ rentPerMonth}), kept
     * beside the live balance so later top-ups never re-derive it from a
     * pricing that may have changed.
     */
    private BigDecimal standardOf(LeaseDeposit deposit) {
        return deposit.getStandardBalance() != null ? deposit.getStandardBalance()
                : deposit.getBalance() != null ? deposit.getBalance() : BigDecimal.ZERO;
    }

    private int nextSequence(UUID leaseId) {
        return splitRepository.findByLeaseIdOrderBySequenceAsc(leaseId).stream()
                .map(LeaseDepositSplit::getSequence)
                .filter(sequence -> sequence != null)
                .collect(Collectors.toList())
                .stream()
                .max(Integer::compareTo)
                .map(max -> max + 1)
                .orElse(1);
    }

    private void fail(LeaseTopUp topUp, String reason) {
        topUp.setStatus(statusResolver.resolve(Entities.LEASE_TOPUPS,
                NotraiusStatuses.LEASE_TOPUP_FAILED));
        topUp.setVersion(topUp.getVersion() + 1);
        topUpRepository.save(topUp);
        log.warn("Top-up {} FAILED: {}", topUp.getId(), reason);
    }

    private void auditTopUp(LeaseTopUp topUp, Map<String, String> data) {
        auditTrail.record(Entities.LEASES, NotraiusActivities.Leases.TOP_UP,
                topUp.getId(), "TOP_UP", null, data);
    }

}