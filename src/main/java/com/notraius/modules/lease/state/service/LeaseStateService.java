package com.notraius.modules.lease.state.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.notraius.common.audit.AuditTrail;
import com.notraius.common.NotraiusErrors;
import com.notraius.common.integration.PayPaymentClient;
import com.notraius.common.services.LeaseAnnouncer;
import com.notraius.common.services.StatusResolver;
import com.notraius.config.properties.NotraiusProperties;
import com.notraius.database.entities.asset.Property;
import com.notraius.database.entities.lease.Lease;
import com.notraius.database.entities.lease.LeaseBreakdown;
import com.notraius.database.entities.lease.LeaseCondition;
import com.notraius.database.entities.lease.LeaseDeposit;
import com.notraius.database.entities.lease.LeaseDepositSplit;
import com.notraius.database.entities.lease.LeaseNotice;
import com.notraius.database.entities.lease.LeaseRepair;
import com.notraius.database.entities.lease.LeaseSettlement;
import com.notraius.database.entities.reference.Entities;
import com.notraius.database.entities.reference.Type;
import com.notraius.database.entities.reference.Validity;
import com.notraius.database.repositories.asset.PropertyRepository;
import com.notraius.database.repositories.lease.LeaseBreakdownRepository;
import com.notraius.database.repositories.lease.LeaseConditionRepository;
import com.notraius.database.repositories.lease.LeaseDepositRepository;
import com.notraius.database.repositories.lease.LeaseDepositSplitRepository;
import com.notraius.database.repositories.lease.LeaseNoticeRepository;
import com.notraius.database.repositories.lease.LeaseRepairRepository;
import com.notraius.database.repositories.lease.LeaseRepository;
import com.notraius.database.repositories.lease.LeaseSettlementRepository;
import com.notraius.database.repositories.reference.TypeRepository;
import com.notraius.modules.core.common.constants.NotraiusActivities;
import com.notraius.modules.core.common.constants.NotraiusStatuses;
import com.notraius.modules.core.common.schedule.SweepJobs;
import com.notraius.modules.core.common.schedule.SweepPager;
import com.notraius.modules.lease.agreement.service.LeaseAgreementService;
import com.notraius.modules.lease.finance.service.LeaseFinanceService;
import com.notraius.modules.lease.state.dto.request.LeaseRepairRequest;
import com.notraius.modules.lease.state.dto.response.LeaseNoticeResponse;
import com.notraius.modules.lease.state.dto.response.LeaseRepairResponse;
import com.notraius.modules.lease.state.dto.response.LeaseSettlementResponse;
import com.notraius.modules.lease.state.mapper.LeaseNoticeMapper;
import com.notraius.modules.lease.state.mapper.LeaseStateMapper;
import lombok.extern.slf4j.Slf4j;
import org.primarion.module.access.consent.service.OwnershipGuard;
import org.primarion.module.errors.BadRequestException;
import org.primarion.module.errors.ConflictException;
import org.primarion.module.errors.ErrorCode;
import org.primarion.module.errors.ForbiddenException;
import org.primarion.module.errors.NotFoundException;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The lease module's state sub-module (PLAN 6.5-6.7) in one service — one door
 * per concern, one service per sub-module.
 * <p>
 * <b>The advance's cushion is the warning.</b> A DEPOSIT notice issues when the
 * advance stands at or below one unit against the standard frozen at birth; the
 * tenant accepts into a grace window or refuses into a VACATION stamp, and
 * silence escalates on its own clock. The stamp writes the tenancy's end on its
 * validity, so a holiday can never invent time the grant never had.
 * <p>
 * <b>Birth frees the property, and debts are never netted.</b> The settlement is
 * born the instant an ACTIVE tenancy ends, disables the property, refunds every
 * still-HELD split and then moves the tenant's debt and the landlord's unearned
 * rent as separate payments — the logs can show each movement alone.
 * <p>
 * <b>Escalation is forced, so DISPUTED is a freeze.</b> Nothing here issues,
 * escalates or cancels while a ticket stands open: the admin's decision is the
 * only arbiter after that, and it reopens the settlement's fortnight rather than
 * ending it.
 */
@Service
@Slf4j
public class LeaseStateService extends OwnershipGuard {

    private final LeaseRepository leaseRepository;
    private final LeaseNoticeRepository noticeRepository;
    private final LeaseDepositRepository depositRepository;
    private final LeaseSettlementRepository settlementRepository;
    private final LeaseBreakdownRepository breakdownRepository;
    private final LeaseDepositSplitRepository splitRepository;
    private final LeaseConditionRepository conditionRepository;
    private final LeaseRepairRepository repairRepository;
    private final PropertyRepository propertyRepository;
    private final TypeRepository typeRepository;
    private final StatusResolver statusResolver;
    private final PayPaymentClient payPaymentClient;
    private final LeaseAgreementService agreementService;
    private final LeaseFinanceService financeService;
    private final NotraiusProperties properties;
    private final LeaseNoticeMapper noticeMapper;
    private final LeaseStateMapper stateMapper;
    private final AuditTrail auditTrail;
    private final SweepPager sweeps;
    private final LeaseAnnouncer announcer;

    private static final String LANDLORD_REFERENCE_PREFIX = "LEASE-SETTLEMENT-LANDLORD:";
    private static final String TENANT_REFERENCE_PREFIX = "LEASE-SETTLEMENT-TENANT:";

    /**
     * The finance sub-module reads this service's notice engine back
     * ({@code evaluateDeposit}, {@code issueVacation}, {@code hasOpenNotice}), so
     * that one seam is lazy: the two sub-modules depend on each other's laws
     * without a constructor cycle.
     */
    public LeaseStateService(
            LeaseRepository leaseRepository,
            LeaseNoticeRepository noticeRepository,
            LeaseDepositRepository depositRepository,
            LeaseSettlementRepository settlementRepository,
            LeaseBreakdownRepository breakdownRepository,
            LeaseDepositSplitRepository splitRepository,
            LeaseConditionRepository conditionRepository,
            LeaseRepairRepository repairRepository,
            PropertyRepository propertyRepository,
            TypeRepository typeRepository,
            StatusResolver statusResolver,
            PayPaymentClient payPaymentClient,
            LeaseAgreementService agreementService,
            @Lazy LeaseFinanceService financeService,
            NotraiusProperties properties,
            LeaseNoticeMapper noticeMapper,
            LeaseStateMapper stateMapper,
            AuditTrail auditTrail,
            SweepPager sweeps,
            LeaseAnnouncer announcer) {
        this.leaseRepository = leaseRepository;
        this.noticeRepository = noticeRepository;
        this.depositRepository = depositRepository;
        this.settlementRepository = settlementRepository;
        this.breakdownRepository = breakdownRepository;
        this.splitRepository = splitRepository;
        this.conditionRepository = conditionRepository;
        this.repairRepository = repairRepository;
        this.propertyRepository = propertyRepository;
        this.typeRepository = typeRepository;
        this.statusResolver = statusResolver;
        this.payPaymentClient = payPaymentClient;
        this.agreementService = agreementService;
        this.financeService = financeService;
        this.properties = properties;
        this.noticeMapper = noticeMapper;
        this.stateMapper = stateMapper;
        this.auditTrail = auditTrail;
        this.sweeps = sweeps;
        this.announcer = announcer;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // The deposit engine and its notices
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Read the cushion and act: close a satisfied notice, or open a DEPOSIT
     * notice when the balance is critically thin. Called at activation, after
     * every consumption and top-up, and by the vigil.
     */
    @Transactional
    public Optional<LeaseNotice> evaluateDeposit(Lease lease) {
        if (!NotraiusStatuses.LEASE_ACTIVE.equals(statusOf(lease))) {
            return Optional.empty();
        }
        LeaseDeposit advance = advanceOf(lease.getId());
        if (advance == null) {
            return Optional.empty();
        }
        BigDecimal standard = standardOf(advance);
        BigDecimal balance = balanceOf(advance);
        Instant now = Instant.now();

        List<LeaseNotice> live = noticeRepository.findOpenOfType(
                lease.getId(), NotraiusStatuses.LEASE_NOTICE_DEPOSIT, now);
        if (!live.isEmpty()) {
            LeaseNotice open = live.get(0);
            if (balance.compareTo(standard) >= 0) {
                satisfy(open, now);
                return Optional.of(open);
            }
            return Optional.of(open);
        }

        if (balance.compareTo(BigDecimal.ONE) <= 0) {
            Instant until = LeaseAgreementService.advance(now, "HOUR", properties.getLeaseNoticeResponseHours());
            LeaseNotice issued = issue(lease, NotraiusStatuses.LEASE_NOTICE_DEPOSIT,
                    reasonFor(balance, standard), until);
            log.info("Lease {} DEPOSIT notice {} issued: advance {} below standard {}",
                    lease.getId(), issued.getId(), balance, standard);
            return Optional.of(issued);
        }
        return Optional.empty();
    }

    /**
     * The vigil's notice pass: escalate unanswered notices whose clock ran out,
     * and accepted graces whose window closed with the cushion still short. A
     * notice whose balance climbed back is closed satisfied instead. VACATION
     * and RECONVERSION notices are not escalated here — their expiry is the
     * tenancy's end, which the end-of-life walk reads directly.
     * <p>
     * <b>Two worklists, both keyset-paged.</b> The unanswered notices are read in
     * {@code (validity end, id)} order, the accepted graces in the same order, and
     * each notice is judged in its own transaction by {@link #escalateUnanswered}
     * or {@link #judgeAnswered} — so a lapse that cannot be answered rolls back
     * alone, and a page of a million notices is a page of ids, never entities.
     * <p>
     * Deliberately not {@code @Transactional}: the pager opens one transaction per
     * notice, which a pass-wide transaction would collapse into one.
     *
     * @return how many notices this pass acted on
     */
    public int escalateLapsed() {
        Instant now = Instant.now();
        List<String> liveLeases = List.of(NotraiusStatuses.LEASE_ACTIVE);

        int unanswered = sweeps.run(SweepJobs.LEASE_VIGIL_NOTICES,
                (afterKey, afterId, limit) -> noticeRepository.findLapsedPage(now,
                        NotraiusStatuses.LEASE_NOTICE_ISSUED, liveLeases, afterKey, afterId,
                        PageRequest.of(0, limit)),
                this::escalateUnanswered);

        int answered = sweeps.run(SweepJobs.LEASE_VIGIL_ANSWERED_NOTICES,
                (afterKey, afterId, limit) -> noticeRepository.findAnsweredLapsedPage(
                        NotraiusStatuses.LEASE_RESPONSE_ACCEPT, now,
                        NotraiusStatuses.LEASE_NOTICE_ACCEPTED, liveLeases, afterKey, afterId,
                        PageRequest.of(0, limit)),
                this::judgeAnswered);

        return unanswered + answered;
    }

    /**
     * One unanswered notice, in its own transaction. The row is re-read here and
     * again guarded by its own response: a notice answered while the pass was
     * walking its page must not be escalated for silence — the answer is the
     * truth, the worklist was a snapshot.
     */
    @Transactional
    public int escalateUnanswered(UUID noticeId) {
        Instant now = Instant.now();
        LeaseNotice notice = noticeRepository.findById(noticeId).orElse(null);
        if (notice == null || notice.getResponse() != null || !isDeposit(notice)) {
            return 0;
        }
        escalate(notice, now, "silence past the notice's response window");
        return 1;
    }

    /**
     * One accepted grace whose window closed, in its own transaction: dissolved
     * satisfied when the balance meanwhile reached the standard, escalated
     * otherwise. Re-read and re-guarded like {@link #escalateUnanswered}.
     */
    @Transactional
    public int judgeAnswered(UUID noticeId) {
        Instant now = Instant.now();
        LeaseNotice notice = noticeRepository.findById(noticeId).orElse(null);
        if (notice == null
                || !NotraiusStatuses.LEASE_RESPONSE_ACCEPT.equals(notice.getResponse())
                || !isDeposit(notice)) {
            return 0;
        }
        LeaseDeposit advance = advanceOf(notice.getLease().getId());
        if (advance != null && balanceOf(advance).compareTo(standardOf(advance)) >= 0) {
            satisfy(notice, now);
        } else {
            escalate(notice, now,
                    "the accepted grace elapsed with the cushion still below the standard");
        }
        return 1;
    }

    /**
     * The imported backlog's borrowed clock (6.6): a RECONVERSION notice whose
     * validity <em>is</em> the tenancy's window of record. It carries no answer
     * door — clearing the backlog or skipping a period decides its fate.
     */
    @Transactional
    public LeaseNotice issueReconversion(Lease lease, Instant until, String reason) {
        Instant now = Instant.now();
        if (until == null || !until.isAfter(now)) {
            throw new ConflictException(NotraiusErrors.LEASE_IMPORT_PERIOD_INVALID,
                    "The reconversion clock must extend into the future");
        }
        Instant priorEnd = lease.getValidity().getToDate();
        LeaseNotice notice = issue(lease, NotraiusStatuses.LEASE_NOTICE_RECONVERSION, reason, until);
        notice.setPreviousValidityToDate(priorEnd);
        noticeRepository.save(notice);
        log.info("Lease {} RECONVERSION issued until {} (prior end {})",
                lease.getId(), until, priorEnd);
        return notice;
    }

    /**
     * The backlog cleared inside its window: the borrowed clock dissolves and the
     * tenancy continues open-ended (6.6's default), the DEPOSIT demand taking
     * over next.
     */
    @Transactional
    public boolean dissolveReconversion(LeaseNotice notice) {
        Lease lease = notice.getLease();
        Instant now = Instant.now();
        notice.setResponse(NotraiusStatuses.LEASE_RESPONSE_SATISFIED);
        notice.setRespondedAt(now);
        notice.getValidity().setStatus(statusResolver.resolve(Entities.LEASE_NOTICES,
                NotraiusStatuses.LEASE_NOTICE_SATISFIED));
        notice.getValidity().setToDate(now);
        noticeRepository.save(notice);

        lease.getValidity().setToDate(null);
        lease.setVersion(lease.getVersion() + 1);
        leaseRepository.save(lease);
        agreementService.sync(lease);
        evaluateDeposit(lease);

        auditNotice(NotraiusActivities.Leases.NOTICE_SATISFY, notice, "DISSOLVE",
                noticeMapper.toResponse(notice));
        announcer.announce(lease, "RECONVERSION_DISSOLVED",
                NotraiusActivities.Leases.NOTICE_SATISFY, null,
                "Reconversion cleared",
                "The imported backlog on lease " + lease.getId()
                        + " is cleared; the tenancy continues open-ended.");
        log.info("Lease {} reconversion {} dissolved; tenancy continues open-ended",
                lease.getId(), notice.getId());
        return true;
    }

    /** Accept a DEPOSIT notice: the grace window opens, counted from now. */
    @Transactional
    public LeaseNoticeResponse accept(UUID noticeId, UUID callerId) {
        LeaseNotice notice = requireNotice(noticeId);
        requireTenant(notice, callerId);
        requireDepositNotice(notice);
        requireUnanswered(notice);
        Instant now = Instant.now();
        requireWindowOpen(notice, now);

        LeaseDeposit advance = requireAdvance(notice.getLease());
        Instant graceEnd = graceEnd(now, notice.getLease(), standardOf(advance));

        notice.setResponse(NotraiusStatuses.LEASE_RESPONSE_ACCEPT);
        notice.setRespondedAt(now);
        notice.getValidity().setStatus(statusResolver.resolve(Entities.LEASE_NOTICES,
                NotraiusStatuses.LEASE_NOTICE_ACCEPTED));
        notice.getValidity().setToDate(graceEnd);
        noticeRepository.save(notice);

        auditNotice(NotraiusActivities.Leases.NOTICE_ACCEPT, notice, "ACCEPT", noticeMapper.toResponse(notice));
        announcer.announce(notice.getLease(), "NOTICE_ACCEPTED",
                NotraiusActivities.Leases.NOTICE_ACCEPT, callerId,
                "Lease notice accepted",
                "The tenant accepted notice " + notice.getId() + "; the grace window closes "
                        + notice.getValidity().getToDate() + ".");
        return noticeMapper.toResponse(notice);
    }

    /** Refuse a DEPOSIT notice: the machine escalates at once. */
    @Transactional
    public LeaseNoticeResponse refuse(UUID noticeId, UUID callerId) {
        LeaseNotice notice = requireNotice(noticeId);
        requireTenant(notice, callerId);
        requireDepositNotice(notice);
        requireUnanswered(notice);
        Instant now = Instant.now();
        requireWindowOpen(notice, now);

        notice.setResponse(NotraiusStatuses.LEASE_RESPONSE_REFUSE);
        notice.setRespondedAt(now);
        notice.getValidity().setStatus(statusResolver.resolve(Entities.LEASE_NOTICES,
                NotraiusStatuses.LEASE_NOTICE_REFUSED));
        notice.getValidity().setToDate(now);
        noticeRepository.save(notice);

        LeaseNotice vacation = issueVacation(notice.getLease(),
                "The tenant refused the deposit notice " + notice.getId());
        auditNotice(NotraiusActivities.Leases.NOTICE_REFUSE, notice, "REFUSE", noticeMapper.toResponse(notice));
        announcer.announce(notice.getLease(), "NOTICE_REFUSED",
                NotraiusActivities.Leases.NOTICE_REFUSE, callerId,
                "Lease notice refused",
                "The tenant refused notice " + notice.getId() + "; the vacation clock follows.");
        log.info("Lease {} notice {} refused; vacation {} follows",
                notice.getLease().getId(), notice.getId(),
                vacation != null ? vacation.getId() : "not issued (no time remained)");
        return noticeMapper.toResponse(notice);
    }

    /**
     * Cancel a VACATION notice: restore the validity the stamp overwrote,
     * re-sync the calendar (a period the restored grant holds is generated
     * again) and return to a fresh DEPOSIT clock. Only the owner may.
     */
    @Transactional
    public LeaseNoticeResponse cancel(UUID noticeId, UUID callerId) {
        LeaseNotice notice = requireNotice(noticeId);
        Lease lease = notice.getLease();
        if (callerId == null || !callerId.equals(lease.getOwnerId())) {
            throw new ForbiddenException(NotraiusErrors.LEASE_NOTICE_FORBIDDEN,
                    "Only the lease's owner may cancel a vacation notice");
        }
        if (notice.getType() == null
                || !NotraiusStatuses.LEASE_NOTICE_VACATION.equals(notice.getType().getCode())) {
            throw new ConflictException(NotraiusErrors.LEASE_STATE_INVALID,
                    "Notice " + noticeId + " is not a VACATION notice; only a vacation is cancellable");
        }
        requireUnanswered(notice);
        if (!NotraiusStatuses.LEASE_ACTIVE.equals(statusOf(lease))) {
            throw new ConflictException(NotraiusErrors.LEASE_STATE_INVALID,
                    "Lease " + lease.getId() + " is " + statusOf(lease)
                            + "; a vacation is only cancellable while the tenancy is ACTIVE");
        }

        Instant now = Instant.now();
        notice.setResponse(NotraiusStatuses.LEASE_RESPONSE_CANCEL);
        notice.setRespondedAt(now);
        notice.getValidity().setStatus(statusResolver.resolve(Entities.LEASE_NOTICES,
                NotraiusStatuses.LEASE_NOTICE_CANCELLED));
        notice.getValidity().setToDate(now);
        noticeRepository.save(notice);

        // The undo: exactly what the stamp overwrote — null restores openness.
        lease.getValidity().setToDate(notice.getPreviousValidityToDate());
        lease.setVersion(lease.getVersion() + 1);
        leaseRepository.save(lease);
        agreementService.sync(lease);
        evaluateDeposit(lease);

        auditNotice(NotraiusActivities.Leases.NOTICE_CANCEL, notice, "CANCEL", noticeMapper.toResponse(notice));
        announcer.announce(lease, "NOTICE_CANCELLED",
                NotraiusActivities.Leases.NOTICE_CANCEL, callerId,
                "Lease vacation cancelled",
                "The vacation notice on lease " + lease.getId()
                        + " was cancelled; the tenancy stands at its restored end.");
        log.info("Lease {} vacation {} cancelled; validity end restored to {}",
                lease.getId(), notice.getId(), notice.getPreviousValidityToDate());
        return noticeMapper.toResponse(notice);
    }

    /**
     * Issue the VACATION stamp: expiry is the earlier of the configured months
     * and the tenancy's own end. A lease with no time left gets no notice — its
     * end is already due and the end-of-life walk owns it.
     */
    @Transactional
    public LeaseNotice issueVacation(Lease lease, String reason) {
        Instant now = Instant.now();
        List<LeaseNotice> live = noticeRepository.findOpenOfType(
                lease.getId(), NotraiusStatuses.LEASE_NOTICE_VACATION, now);
        if (!live.isEmpty()) {
            return live.get(0);
        }
        Instant priorEnd = lease.getValidity().getToDate();
        Instant expiry = LeaseAgreementService.earlier(
                LeaseAgreementService.plusMonths(now, properties.getLeaseVacationMonths()), priorEnd);
        if (expiry == null || !expiry.isAfter(now)) {
            log.info("Lease {} has no time left to vacate; no VACATION notice issued", lease.getId());
            return null;
        }

        LeaseNotice notice = issue(lease, NotraiusStatuses.LEASE_NOTICE_VACATION, reason, expiry);
        notice.setPreviousValidityToDate(priorEnd);
        noticeRepository.save(notice);

        lease.getValidity().setToDate(expiry);
        lease.setVersion(lease.getVersion() + 1);
        leaseRepository.save(lease);
        agreementService.sync(lease);

        log.info("Lease {} VACATION issued until {} (prior end {}); calendar re-synced",
                lease.getId(), expiry, priorEnd);
        return notice;
    }

    /** Whether a notice of this kind still stands open on the lease. */
    @Transactional(readOnly = true)
    public boolean hasOpenNotice(UUID leaseId, String typeCode) {
        return !noticeRepository.findOpenOfType(leaseId, typeCode, Instant.now()).isEmpty();
    }

    @Transactional(readOnly = true)
    public List<LeaseNoticeResponse> list(UUID leaseId, UUID callerId) {
        Lease lease = leaseRepository.findById(leaseId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.LEASE_NOT_FOUND,
                        "Lease not found: " + leaseId));
        requireNoticeParty(lease, callerId);
        return noticeRepository.findByLeaseIdOrderByCreatedAtDesc(leaseId).stream()
                .map(noticeMapper::toResponse)
                .toList();
    }

    private LeaseNotice issue(Lease lease, String typeCode, String reason, Instant until) {
        Type type = typeRepository.findByEntityCodeAndCode(Entities.LEASE_NOTICES, typeCode)
                .orElseThrow(() -> new IllegalStateException(
                        "No notice type '" + typeCode + "' is seeded for LEASE_NOTICES"));
        Validity validity = statusResolver.openValidity(Entities.LEASE_NOTICES,
                NotraiusStatuses.LEASE_NOTICE_ISSUED);
        validity.setToDate(until);
        validity.setStatus(statusResolver.resolve(Entities.LEASE_NOTICES, NotraiusStatuses.LEASE_NOTICE_ISSUED));

        LeaseNotice notice = new LeaseNotice();
        notice.setLease(lease);
        notice.setType(type);
        notice.setReason(reason);
        notice.setValidity(validity);
        notice.setVersion(1);
        noticeRepository.save(notice);
        auditNotice(NotraiusActivities.Leases.NOTICE_ISSUE, notice, "ISSUE", noticeMapper.toResponse(notice));
        announcer.announce(lease, typeCode, NotraiusActivities.Leases.NOTICE_ISSUE, null,
                "Lease notice issued",
                "A " + typeCode + " notice was issued on lease " + lease.getId()
                        + "; answer before " + until + ".");
        return notice;
    }

    private void escalate(LeaseNotice notice, Instant now, String reason) {
        notice.getValidity().setStatus(statusResolver.resolve(Entities.LEASE_NOTICES,
                NotraiusStatuses.LEASE_NOTICE_ESCALATED));
        notice.getValidity().setToDate(now);
        notice.setRespondedAt(now);
        noticeRepository.save(notice);
        auditNotice(NotraiusActivities.Leases.NOTICE_ESCALATE, notice, "ESCALATE", noticeMapper.toResponse(notice));
        announcer.announce(notice.getLease(), "NOTICE_ESCALATED",
                NotraiusActivities.Leases.NOTICE_ESCALATE, null,
                "Lease notice escalated",
                "Notice " + notice.getId() + " on lease " + notice.getLease().getId()
                        + " lapsed unanswered: " + reason + ".");

        LeaseNotice vacation = issueVacation(notice.getLease(), reason);
        log.info("Lease {} notice {} escalated ({}); vacation={}",
                notice.getLease().getId(), notice.getId(), reason,
                vacation != null ? vacation.getId() : "none");
    }

    private void satisfy(LeaseNotice notice, Instant now) {
        notice.setResponse(NotraiusStatuses.LEASE_RESPONSE_SATISFIED);
        notice.setRespondedAt(now);
        notice.getValidity().setStatus(statusResolver.resolve(Entities.LEASE_NOTICES,
                NotraiusStatuses.LEASE_NOTICE_SATISFIED));
        notice.getValidity().setToDate(now);
        noticeRepository.save(notice);
        auditNotice(NotraiusActivities.Leases.NOTICE_SATISFY, notice, "SATISFY", noticeMapper.toResponse(notice));
        announcer.announce(notice.getLease(), "NOTICE_SATISFIED",
                NotraiusActivities.Leases.NOTICE_SATISFY, null,
                "Lease notice satisfied",
                "Notice " + notice.getId() + " on lease " + notice.getLease().getId()
                        + " closed itself: the cushion reached its standard.");
    }

    /**
     * Grace: the frozen standard × the multiplier, in the pricing's own cadence.
     * A zero counter has no units to double, so its window is the response
     * window itself — the only duration the case honestly owns (6.5).
     */
    private Instant graceEnd(Instant now, Lease lease, BigDecimal standard) {
        long units = standard.multiply(BigDecimal.valueOf(properties.getLeaseDepositGraceMultiplier()))
                .setScale(0, RoundingMode.CEILING).longValueExact();
        if (units <= 0) {
            return LeaseAgreementService.advance(now, "HOUR", properties.getLeaseNoticeResponseHours());
        }
        // The signed cadence (V39): a grace window measured in the units this
        // tenancy was billed in, not in today's offer.
        String unit = lease.getDuration().getType().getCode();
        return LeaseAgreementService.advance(now, unit, units);
    }

    private String reasonFor(BigDecimal balance, BigDecimal standard) {
        return "The advance cushion stands at " + balance + " against a standard of " + standard;
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

    private LeaseDeposit requireAdvance(Lease lease) {
        LeaseDeposit advance = advanceOf(lease.getId());
        if (advance == null) {
            throw new ConflictException(NotraiusErrors.LEASE_STATE_INVALID,
                    "Lease " + lease.getId() + " holds no ADVANCE deposit, so no cushion exists to pass");
        }
        return advance;
    }

    private BigDecimal standardOf(LeaseDeposit deposit) {
        return deposit.getStandardBalance() != null ? deposit.getStandardBalance()
                : deposit.getBalance() != null ? deposit.getBalance() : BigDecimal.ZERO;
    }

    private BigDecimal balanceOf(LeaseDeposit deposit) {
        return deposit.getBalance() != null ? deposit.getBalance() : BigDecimal.ZERO;
    }

    private boolean isDeposit(LeaseNotice notice) {
        return notice.getType() != null
                && NotraiusStatuses.LEASE_NOTICE_DEPOSIT.equals(notice.getType().getCode());
    }

    private LeaseNotice requireNotice(UUID noticeId) {
        return noticeRepository.findById(noticeId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.LEASE_NOTICE_NOT_FOUND,
                        "Lease notice not found: " + noticeId));
    }

    private void requireTenant(LeaseNotice notice, UUID callerId) {
        if (callerId == null || !callerId.equals(notice.getLease().getUserId())) {
            throw new ForbiddenException(NotraiusErrors.LEASE_NOTICE_FORBIDDEN,
                    "Only the tenant may answer a lease notice");
        }
    }

    /**
     * The notice board is a party's business. Routed through the estate's
     * ownership guard (never a bare id comparison) so a seat or an app holding
     * a live consent for the tenant/landlord reaches the same board its grantor
     * would — and a caller holding neither is refused.
     */
    private void requireNoticeParty(Lease lease, UUID callerId) {
        requireParty("Only a party to lease " + lease.getId() + " may read its notices",
                NotraiusErrors.LEASE_PARTY_FORBIDDEN, lease, callerId);
    }

    private void requireDepositNotice(LeaseNotice notice) {
        if (!isDeposit(notice)) {
            throw new ForbiddenException(NotraiusErrors.LEASE_NOTICE_FORBIDDEN,
                    "Only a DEPOSIT notice carries a tenant's answer; a VACATION is the owner's to cancel");
        }
    }

    private void requireUnanswered(LeaseNotice notice) {
        if (notice.getResponse() != null) {
            throw new ConflictException(NotraiusErrors.LEASE_NOTICE_ALREADY_ANSWERED,
                    "Notice " + notice.getId() + " already carries the answer '"
                            + notice.getResponse() + "'");
        }
    }

    private void requireWindowOpen(LeaseNotice notice, Instant now) {
        if (notice.getValidity().getToDate() == null || !notice.getValidity().getToDate().isAfter(now)) {
            throw new ConflictException(NotraiusErrors.LEASE_NOTICE_WINDOW_CLOSED,
                    "Notice " + notice.getId() + "'s window closed at "
                            + notice.getValidity().getToDate());
        }
    }

    private void auditNotice(String activityCode, LeaseNotice notice, String operation, Object after) {
        auditTrail.record(Entities.LEASE_NOTICES, activityCode, notice.getId(), operation, null, after);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // The settlement: birth, debts, confirmation
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Open the settlement for an ended tenancy: one row, the property disabled,
     * the escrows returned and the two debts moved as separate payments.
     * Idempotent — a second call finds the standing settlement.
     */
    @Transactional
    public LeaseSettlementResponse birth(Lease lease, UUID actor, String reason) {
        Optional<LeaseSettlement> existing = settlementRepository.findByLeaseId(lease.getId());
        if (existing.isPresent()) {
            return read(existing.get(), lease);
        }

        Instant now = Instant.now();
        LeaseSettlement row = new LeaseSettlement();
        row.setLease(lease);
        row.setStatus(statusResolver.resolve(Entities.LEASE_SETTLEMENTS,
                NotraiusStatuses.LEASE_SETTLEMENT_OPEN));
        row.setDeadline(now.plusSeconds(properties.getLeaseSettlementWindowDays() * 86_400L));
        row.setMoveOutDeadline(now.plusSeconds(properties.getLeaseMoveOutWindowDays() * 86_400L));
        row.setVersion(1);
        row.setCreatedBy(actor);
        settlementRepository.save(row);

        disableProperty(lease);
        refundHeldCustody(lease, "settlement birth");
        payLandlordDebt(lease, row);
        chargeTenantDebt(lease, row);

        auditSettlement(NotraiusActivities.Leases.SETTLE, row, "SETTLE", Map.of(
                "leaseId", lease.getId().toString(),
                "reason", reason != null ? reason : "validity ended"));
        announcer.announce(lease, "SETTLEMENT_BORN", NotraiusActivities.Leases.SETTLE, actor,
                "Lease settlement opened",
                "The tenancy on lease " + lease.getId() + " ended; its settlement is open until "
                        + row.getDeadline() + ".");
        log.info("Settlement {} born for lease {} ({}); property {} DISABLED",
                row.getId(), lease.getId(), reason,
                lease.getPricing().getProperty() != null
                        ? lease.getPricing().getProperty().getId() : null);
        return read(row, lease);
    }

    /** The final word: everything owed is answered; the lease becomes COMPLETED. */
    @Transactional
    public LeaseSettlementResponse confirm(UUID settlementId, UUID callerId) {
        LeaseSettlement row = settlementRepository.findById(settlementId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.LEASE_SETTLEMENT_NOT_FOUND,
                        "Lease settlement not found: " + settlementId));
        Lease lease = row.getLease();
        requireSettlementParty(lease, callerId);
        String status = row.getStatus() != null ? row.getStatus().getCode() : null;
        if (!NotraiusStatuses.LEASE_SETTLEMENT_OPEN.equals(status)) {
            throw new ConflictException(NotraiusErrors.LEASE_SETTLEMENT_NOT_OPEN,
                    "Settlement " + settlementId + " is " + status
                            + "; an overdue settlement is the dispute door's");
        }

        List<LeaseBreakdown> owed = tenantOwed(lease);
        if (!owed.isEmpty()) {
            throw new ConflictException(NotraiusErrors.LEASE_SETTLEMENT_OUTSTANDING,
                    "Settlement " + settlementId + " still owes " + owed.size()
                            + " billed row(s); the golden rule pays debts, never skips them");
        }
        List<?> pendingConditions = conditionRepository.findByLeaseAndType(lease.getId(),
                NotraiusStatuses.LEASE_CONDITION_AFTER_MOVE_OUT).stream()
                .filter(c -> c.getStatus() != null
                        && NotraiusStatuses.LEASE_CONDITION_PENDING.equals(c.getStatus().getCode()))
                .toList();
        if (isCheckable(lease) && !pendingConditions.isEmpty()) {
            throw new ConflictException(NotraiusErrors.LEASE_SETTLEMENT_OUTSTANDING,
                    "Settlement " + settlementId + " still owes " + pendingConditions.size()
                            + " unvalidated move-out condition(s)");
        }

        // The refund dream's last pass: whatever a severed call left HELD goes
        // back now, before any word of completion.
        refundHeldCustody(lease, "settlement confirmation");

        row.setStatus(statusResolver.resolve(Entities.LEASE_SETTLEMENTS,
                NotraiusStatuses.LEASE_SETTLEMENT_CONFIRMED));
        row.setConfirmedAt(Instant.now());
        row.setConfirmedBy(callerId);
        row.setVersion(row.getVersion() + 1);
        settlementRepository.save(row);

        lease.setStatus(statusResolver.resolve(Entities.LEASES, NotraiusStatuses.LEASE_COMPLETED));
        lease.setVersion(lease.getVersion() + 1);
        leaseRepository.save(lease);

        auditSettlement(NotraiusActivities.Leases.SETTLEMENT_CONFIRM, row, "CONFIRM", Map.of(
                "leaseId", lease.getId().toString(),
                "confirmedBy", String.valueOf(callerId)));
        announcer.announce(lease, "SETTLEMENT_CONFIRMED",
                NotraiusActivities.Leases.SETTLEMENT_CONFIRM, callerId,
                "Lease completed",
                "Settlement " + settlementId + " is confirmed; lease " + lease.getId()
                        + " is completed and the property leaves the market.");
        log.info("Settlement {} confirmed by {}; lease {} COMPLETED",
                settlementId, callerId, lease.getId());
        return read(row, lease);
    }

    @Transactional(readOnly = true)
    public LeaseSettlementResponse get(UUID leaseId, UUID callerId) {
        LeaseSettlement row = settlementRepository.findByLeaseId(leaseId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.LEASE_SETTLEMENT_NOT_FOUND,
                        "No settlement stands for lease " + leaseId));
        requireSettlementParty(row.getLease(), callerId);
        return read(row, row.getLease());
    }

    /**
     * Resolution's reply to an OVERDUE settlement (6.7): the admin's decision is
     * the only arbiter of a state both sides failed to finish, so the row reopens
     * with a fresh fortnight and the ordinary laws re-run over it.
     */
    @Transactional
    public void reopenForResolution(UUID leaseId) {
        Optional<LeaseSettlement> found = settlementRepository.findByLeaseId(leaseId);
        if (found.isEmpty()) {
            return;
        }
        LeaseSettlement row = found.get();
        if (!NotraiusStatuses.LEASE_SETTLEMENT_OVERDUE.equals(
                row.getStatus() != null ? row.getStatus().getCode() : null)) {
            return;
        }
        row.setStatus(statusResolver.resolve(Entities.LEASE_SETTLEMENTS,
                NotraiusStatuses.LEASE_SETTLEMENT_OPEN));
        row.setDeadline(Instant.now().plusSeconds(properties.getLeaseSettlementWindowDays() * 86_400L));
        row.setVersion(row.getVersion() + 1);
        settlementRepository.save(row);
        auditSettlement(NotraiusActivities.Leases.RESOLVE, row, "REOPEN", Map.of(
                "leaseId", leaseId.toString()));
        log.info("Settlement {} of lease {} reopened by resolution; deadline {}",
                row.getId(), leaseId, row.getDeadline());
    }

    /** The billed fortnight lapsed: OPEN becomes OVERDUE, the disputes' door. */
    @Transactional
    public void markOverdue(UUID settlementId) {
        LeaseSettlement row = settlementRepository.findById(settlementId).orElse(null);
        if (row == null) {
            return;
        }
        row.setStatus(statusResolver.resolve(Entities.LEASE_SETTLEMENTS,
                NotraiusStatuses.LEASE_SETTLEMENT_OVERDUE));
        row.setVersion(row.getVersion() + 1);
        settlementRepository.save(row);
        auditSettlement(NotraiusActivities.Leases.OVERDUE, row, "OVERDUE", Map.of(
                "leaseId", row.getLease().getId().toString()));
        log.warn("Settlement {} of lease {} turned OVERDUE at {}",
                row.getId(), row.getLease().getId(), row.getDeadline());
    }

    private void disableProperty(Lease lease) {
        Property property = lease.getPricing().getProperty();
        if (property == null) {
            return;
        }
        if (!NotraiusStatuses.PROPERTY_DISABLED.equals(
                property.getStatus() != null ? property.getStatus().getCode() : null)) {
            property.setStatus(statusResolver.resolve(Entities.PROPERTY,
                    NotraiusStatuses.PROPERTY_DISABLED));
            property.setVersion(property.getVersion() + 1);
            propertyRepository.save(property);
        }
    }

    /** Action 1: every still-HELD escrow returns whole; released ones are never revisited. */
    private void refundHeldCustody(Lease lease, String reason) {
        List<LeaseDepositSplit> held = splitRepository.findByLeaseIdAndStatusCodeOrderBySequenceAsc(
                lease.getId(), NotraiusStatuses.LEASE_SPLIT_HELD);
        int refunded = 0;
        for (LeaseDepositSplit split : held) {
            try {
                payPaymentClient.refundSplit(split.getPaymentId(), split.getSplitId());
            } catch (RuntimeException e) {
                // Fail closed: the split stays HELD, and the next pass retries.
                log.warn("Settlement refund of split {} failed for lease {} ({}): {}",
                        split.getSplitId(), lease.getId(), reason, e.getMessage());
                continue;
            }
            split.setStatus(statusResolver.resolve(Entities.LEASE_DEPOSIT_SPLITS,
                    NotraiusStatuses.LEASE_SPLIT_REFUNDED));
            splitRepository.save(split);
            refunded++;
            closeIfDrained(split.getDeposit());
        }
        if (refunded > 0) {
            log.info("Settlement refunded {} HELD split(s) of lease {} ({})",
                    refunded, lease.getId(), reason);
        }
    }

    private void closeIfDrained(LeaseDeposit deposit) {
        if (deposit == null) {
            return;
        }
        long held = splitRepository.findByLeaseIdAndStatusCodeOrderBySequenceAsc(
                        deposit.getLease().getId(), NotraiusStatuses.LEASE_SPLIT_HELD).stream()
                .filter(split -> split.getDeposit() != null
                        && split.getDeposit().getId().equals(deposit.getId()))
                .count();
        if (held == 0) {
            deposit.setStatus(statusResolver.resolve(Entities.LEASE_DEPOSITS,
                    NotraiusStatuses.LEASE_DEPOSIT_RETURNED));
            deposit.setBalance(BigDecimal.ZERO);
            deposit.setVersion(deposit.getVersion() + 1);
            depositRepository.save(deposit);
        }
    }

    /** Unearned rent a reeled horizon left behind: the landlord's own debt. */
    private void payLandlordDebt(Lease lease, LeaseSettlement row) {
        BigDecimal unearned = unearnedRent(lease);
        if (unearned.signum() <= 0) {
            return;
        }
        UUID currencyId = currencyOf(lease);
        if (currencyId == null) {
            log.warn("Lease {} owes unearned rent {} but its property has no currency",
                    lease.getId(), unearned);
            return;
        }
        try {
            payPaymentClient.createDirectPayment(lease.getOwnerId(), currencyId, unearned,
                    NotraiusActivities.Leases.SETTLE, reference(LANDLORD_REFERENCE_PREFIX, row.getId()),
                    lease.getUserId(), "Unearned rent returned — lease " + lease.getId());
            log.info("Settlement {} returned {} of unearned rent to tenant {}",
                    row.getId(), unearned, lease.getUserId());
        } catch (RuntimeException e) {
            log.warn("Settlement {} could not return unearned rent {}: {}",
                    row.getId(), unearned, e.getMessage());
        }
    }

    /** The tenant's exact remainder, unnetted, paid as its own movement. */
    private void chargeTenantDebt(Lease lease, LeaseSettlement row) {
        List<LeaseBreakdown> owed = tenantOwed(lease);
        BigDecimal total = owed.stream().map(LeaseBreakdown::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() <= 0) {
            return;
        }
        UUID currencyId = currencyOf(lease);
        if (currencyId == null) {
            log.warn("Lease {} owes {} but its property has no currency", lease.getId(), total);
            return;
        }
        try {
            payPaymentClient.createDirectPayment(lease.getUserId(), currencyId, total,
                    NotraiusActivities.Leases.SETTLE, reference(TENANT_REFERENCE_PREFIX, row.getId()),
                    lease.getOwnerId(), "Settlement debt — lease " + lease.getId());
        } catch (RestClientResponseException e) {
            // A refused wallet charge leaves the bills standing; their deadlines
            // still run and the tenant can pay each row itself.
            log.warn("Settlement {} tenant debt {} refused ({}); bills stand",
                    row.getId(), total, e.getStatusCode().value());
            return;
        } catch (RuntimeException e) {
            log.warn("Settlement {} tenant debt {} unanswered ({}); bills stand",
                    row.getId(), total, e.getMessage());
            return;
        }
        Instant now = Instant.now();
        for (LeaseBreakdown bill : owed) {
            bill.setStatus(statusResolver.resolve(Entities.LEASE_BREAKDOWNS,
                    NotraiusStatuses.LEASE_BREAKDOWN_SETTLED));
            bill.setSettledAt(now);
            bill.setVersion(bill.getVersion() + 1);
            breakdownRepository.save(bill);
        }
        log.info("Settlement {} charged {} of tenant debt ({} billed row(s))",
                row.getId(), total, owed.size());
    }

    private List<LeaseBreakdown> tenantOwed(Lease lease) {
        return breakdownRepository.findTenantOwed(lease.getId(),
                List.of(NotraiusStatuses.LEASE_BREAKDOWN_PENDING,
                        NotraiusStatuses.LEASE_BREAKDOWN_OVERDUE),
                Entities.LEASES, Entities.LEASE_REPAIRS, lease.getUserId());
    }

    /** Settled installments whose period the final validity never granted. */
    private BigDecimal unearnedRent(Lease lease) {
        Instant end = lease.getValidity() != null ? lease.getValidity().getToDate() : null;
        if (end == null) {
            return BigDecimal.ZERO;
        }
        return breakdownRepository.findByLeaseIdOrderByDeadlineAsc(lease.getId()).stream()
                .filter(row -> NotraiusStatuses.LEASE_BREAKDOWN_INSTALLMENT.equals(
                        row.getType() != null ? row.getType().getCode() : null))
                .filter(row -> NotraiusStatuses.LEASE_BREAKDOWN_SETTLED.equals(
                        row.getStatus() != null ? row.getStatus().getCode() : null))
                .filter(row -> row.getPeriodStart() != null && row.getPeriodStart().isAfter(end))
                .map(LeaseBreakdown::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private LeaseSettlementResponse read(LeaseSettlement row, Lease lease) {
        LeaseSettlementResponse response = stateMapper.toResponse(row);
        BigDecimal tenantDebt = tenantOwed(lease).stream().map(LeaseBreakdown::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal held = splitRepository.findByLeaseIdAndStatusCodeOrderBySequenceAsc(
                        lease.getId(), NotraiusStatuses.LEASE_SPLIT_HELD).stream()
                .map(LeaseDepositSplit::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        response.setTenantDebt(tenantDebt);
        response.setHeldCustody(held);
        response.setLandlordDebt(held.add(unearnedRent(lease)));
        return response;
    }

    private UUID currencyOf(Lease lease) {
        return lease.getPricing().getProperty() != null
                ? lease.getPricing().getProperty().getCurrencyId() : null;
    }

    private boolean isCheckable(Lease lease) {
        return lease.isCheckable()
                && !NotraiusStatuses.LEASE_ORIGIN_IMPORT.equalsIgnoreCase(lease.getOrigin());
    }

    private UUID reference(String prefix, UUID settlementId) {
        return UUID.nameUUIDFromBytes((prefix + settlementId).getBytes(StandardCharsets.UTF_8));
    }

    private void requireSettlementParty(Lease lease, UUID callerId) {
        requireParty("Only a party to lease " + lease.getId() + " may move its settlement",
                NotraiusErrors.LEASE_SETTLEMENT_FORBIDDEN, lease, callerId);
    }

    private void auditSettlement(String activityCode, LeaseSettlement row, String operation, Map<String, String> data) {
        auditTrail.record(Entities.LEASE_SETTLEMENTS, activityCode, row.getId(), operation, null, data);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // The door out of a live tenancy
    // ═══════════════════════════════════════════════════════════════════════

    /** A party ends the tenancy now: the validity's end becomes this instant. */
    @Transactional
    public LeaseSettlementResponse end(UUID leaseId, UUID callerId, String reason) {
        Lease lease = requireLease(leaseId);
        requireParty("Only a party to lease " + leaseId + " may end it",
                NotraiusErrors.LEASE_SETTLEMENT_FORBIDDEN, lease, callerId);
        if (!NotraiusStatuses.LEASE_ACTIVE.equalsIgnoreCase(statusOf(lease))) {
            throw new ConflictException(NotraiusErrors.LEASE_END_WINDOW_CLOSED,
                    "Lease " + leaseId + " is " + statusOf(lease)
                            + "; only an ACTIVE tenancy reaches its end of life");
        }
        if (NotraiusStatuses.LEASE_DISPUTED.equalsIgnoreCase(statusOf(lease))) {
            throw new ConflictException(NotraiusErrors.LEASE_DISPUTE_INVALID,
                    "Lease " + leaseId + " is DISPUTED; only a human decision moves it");
        }

        Instant now = Instant.now();
        Instant priorEnd = lease.getValidity().getToDate();
        Instant finalEnd = priorEnd == null || priorEnd.isAfter(now) ? now : priorEnd;
        lease.getValidity().setToDate(finalEnd);
        lease.setVersion(lease.getVersion() + 1);
        leaseRepository.save(lease);
        agreementService.sync(lease);

        auditEndOfLife(lease, "END", reason);
        log.info("Lease {} ended by {} at {} (prior end {})", leaseId, callerId, finalEnd, priorEnd);
        return birth(lease, callerId,
                reason != null ? reason : "a party ended the tenancy");
    }

    /**
     * The vigil's read: an ACTIVE lease whose validity has ended walks the same
     * door, in its own transaction. Idempotent — a standing settlement answers.
     */
    @Transactional
    public void closeIfDue(UUID leaseId) {
        Lease lease = leaseRepository.findById(leaseId).orElse(null);
        if (lease == null
                || !NotraiusStatuses.LEASE_ACTIVE.equalsIgnoreCase(statusOf(lease))
                || lease.getValidity() == null || lease.getValidity().getToDate() == null
                || lease.getValidity().getToDate().isAfter(Instant.now())) {
            return;
        }
        if (NotraiusStatuses.LEASE_DISPUTED.equalsIgnoreCase(statusOf(lease))) {
            return;
        }
        auditEndOfLife(lease, "END", "the validity ended");
        log.info("Lease {} reached its validity end {}; settlement follows",
                leaseId, lease.getValidity().getToDate());
        birth(lease, null, "the validity ended");
    }

    private void auditEndOfLife(Lease lease, String operation, String reason) {
        auditTrail.record(Entities.LEASES, NotraiusActivities.Leases.END, lease.getId(), operation,
                null, Map.of(
                        "leaseId", lease.getId().toString(),
                        "reason", reason != null ? reason : ""));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // The move-out repair negotiation
    // ═══════════════════════════════════════════════════════════════════════

    /** The landlord lists a repair, with its declared charge if any. */
    @Transactional
    public LeaseRepairResponse report(UUID leaseId, UUID callerId, LeaseRepairRequest request) {
        Lease lease = requireLease(leaseId);
        if (callerId == null || !callerId.equals(lease.getOwnerId())) {
            throw new ForbiddenException(NotraiusErrors.LEASE_REPAIR_FORBIDDEN,
                    "Only the landlord lists repairs");
        }
        if (!NotraiusStatuses.LEASE_ACTIVE.equalsIgnoreCase(statusOf(lease))) {
            throw new ConflictException(NotraiusErrors.LEASE_STATE_INVALID,
                    "Lease " + leaseId + " is " + statusOf(lease) + ", so no move-out stage is open");
        }
        if (NotraiusStatuses.LEASE_DISPUTED.equalsIgnoreCase(statusOf(lease))) {
            throw new ConflictException(NotraiusErrors.LEASE_DISPUTE_INVALID,
                    "Lease " + leaseId + " is DISPUTED; only a human decision moves it");
        }
        if (!lease.isCheckable()
                || NotraiusStatuses.LEASE_ORIGIN_IMPORT.equalsIgnoreCase(lease.getOrigin())) {
            throw new ConflictException(NotraiusErrors.LEASE_CONDITION_INVALID,
                    "Lease " + leaseId + " owes no move-out stage; its settlement is degenerate");
        }
        LeaseSettlement settlement = requireOpenMoveOutWeek(lease);

        LeaseCondition condition = conditionRepository.findById(request.getConditionId())
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.LEASE_CONDITION_INVALID,
                        "Lease condition not found: " + request.getConditionId()));
        if (!condition.getLease().getId().equals(leaseId)
                || condition.getType() == null
                || !NotraiusStatuses.LEASE_CONDITION_AFTER_MOVE_OUT.equals(condition.getType().getCode())) {
            throw new ConflictException(NotraiusErrors.LEASE_CONDITION_INVALID,
                    "Repair must be listed against a move-out condition of lease " + leaseId);
        }

        BigDecimal charge = request.getChargeAmount();
        boolean charged = charge != null && charge.signum() > 0;
        if (charged && request.getPayerId() == null) {
            throw new BadRequestException(NotraiusErrors.LEASE_REPAIR_AMOUNT_INVALID,
                    "A charged repair must declare who pays it");
        }
        if (charged && !request.getPayerId().equals(lease.getUserId())
                && !request.getPayerId().equals(lease.getOwnerId())) {
            throw new BadRequestException(NotraiusErrors.LEASE_REPAIR_AMOUNT_INVALID,
                    "A repair's payer must be a party to the lease");
        }

        LeaseRepair repair = new LeaseRepair();
        repair.setCondition(condition);
        repair.setLease(lease);
        repair.setTitle(request.getTitle().trim());
        repair.setDescription(request.getDescription());
        repair.setStatus(statusResolver.resolve(Entities.LEASE_REPAIRS,
                NotraiusStatuses.LEASE_CONDITION_PENDING));
        repair.setVersion(1);
        repair.setCreatedBy(callerId);
        repairRepository.save(repair);

        UUID chargeBreakdownId = null;
        if (charged) {
            chargeBreakdownId = writeCharge(lease, settlement, repair, charge, request.getPayerId(), callerId);
        }

        auditRepair(NotraiusActivities.Leases.REPAIR_CREATE, repair, "REPAIR_CREATE", Map.of(
                "leaseId", lease.getId().toString(),
                "charged", String.valueOf(charged),
                "payerId", String.valueOf(request.getPayerId())));
        log.info("Lease {} repair {} listed by {} (charge {} payer {})",
                leaseId, repair.getId(), callerId, charged ? charge : "none", request.getPayerId());
        return toResponse(repair, chargeBreakdownId);
    }

    /** The tenant's confirmation of a listed repair. */
    @Transactional
    public LeaseRepairResponse confirmRepair(UUID repairId, UUID callerId) {
        LeaseRepair repair = repairRepository.findById(repairId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.LEASE_REPAIR_NOT_FOUND,
                        "Lease repair not found: " + repairId));
        Lease lease = repair.getLease();
        if (callerId == null || !callerId.equals(lease.getUserId())) {
            throw new ForbiddenException(NotraiusErrors.LEASE_REPAIR_FORBIDDEN,
                    "Only the tenant confirms a listed repair");
        }
        String status = repair.getStatus() != null ? repair.getStatus().getCode() : null;
        if (!NotraiusStatuses.LEASE_CONDITION_PENDING.equals(status)) {
            throw new ConflictException(NotraiusErrors.LEASE_CONDITION_INVALID,
                    "Repair " + repairId + " is " + status + ", not PENDING");
        }
        if (NotraiusStatuses.LEASE_DISPUTED.equalsIgnoreCase(statusOf(lease))) {
            throw new ConflictException(NotraiusErrors.LEASE_DISPUTE_INVALID,
                    "Lease " + lease.getId() + " is DISPUTED; only a human decision moves it");
        }

        repair.setStatus(statusResolver.resolve(Entities.LEASE_REPAIRS,
                NotraiusStatuses.LEASE_CONDITION_CONFIRMED));
        repair.setVersion(repair.getVersion() + 1);
        repairRepository.save(repair);
        auditRepair(NotraiusActivities.Leases.REPAIR_CONFIRM, repair, "REPAIR_CONFIRM", Map.of(
                "leaseId", lease.getId().toString()));
        return read(repair);
    }

    @Transactional(readOnly = true)
    public List<LeaseRepairResponse> listRepairs(UUID leaseId, UUID callerId) {
        Lease lease = requireLease(leaseId);
        requireParty("Only a party to lease " + leaseId + " may read its repairs",
                NotraiusErrors.LEASE_PARTY_FORBIDDEN, lease, callerId);
        return repairRepository.findByLeaseIdOrderByCreatedAtAsc(leaseId).stream()
                .map(this::read)
                .toList();
    }

    private UUID writeCharge(Lease lease, LeaseSettlement settlement, LeaseRepair repair,
                             BigDecimal amount, UUID payerId, UUID actor) {
        Type repairType = typeRepository
                .findByEntityCodeAndCode(Entities.LEASE_BREAKDOWNS, NotraiusStatuses.LEASE_BREAKDOWN_REPAIR)
                .orElseThrow(() -> new IllegalStateException(
                        "No REPAIR breakdown type is seeded for LEASE_BREAKDOWNS"));
        LeaseBreakdown charge = new LeaseBreakdown();
        charge.setEntity(statusResolver.resolveEntity(Entities.LEASE_REPAIRS));
        charge.setReferenceId(repair.getId());
        charge.setLease(lease);
        charge.setTitle("Repair: " + repair.getTitle());
        charge.setDescription(repair.getDescription());
        charge.setAmount(amount);
        charge.setType(repairType);
        charge.setStatus(statusResolver.resolve(Entities.LEASE_BREAKDOWNS,
                NotraiusStatuses.LEASE_BREAKDOWN_PENDING));
        charge.setUserId(payerId);
        // The billed sides' fortnight, never the move-out week (6.7).
        charge.setDeadline(settlement.getDeadline());
        charge.setVersion(1);
        charge.setCreatedBy(actor);
        breakdownRepository.save(charge);
        return charge.getId();
    }

    private LeaseSettlement requireOpenMoveOutWeek(Lease lease) {
        LeaseSettlement settlement = settlementRepository.findByLeaseId(lease.getId())
                .orElseThrow(() -> new ConflictException(NotraiusErrors.LEASE_SETTLEMENT_NOT_OPEN,
                        "Lease " + lease.getId() + " has no settlement, so no move-out stage is open"));
        String status = settlement.getStatus() != null ? settlement.getStatus().getCode() : null;
        Instant weekEnds = settlement.getMoveOutDeadline() != null
                ? settlement.getMoveOutDeadline() : settlement.getDeadline();
        if (!NotraiusStatuses.LEASE_SETTLEMENT_OPEN.equals(status)
                || weekEnds == null || Instant.now().isAfter(weekEnds)) {
            throw new ConflictException(NotraiusErrors.LEASE_SETTLEMENT_NOT_OPEN,
                    "The move-out week of lease " + lease.getId() + " is closed (status "
                            + status + ", deadline " + weekEnds + ")");
        }
        return settlement;
    }

    private LeaseRepairResponse read(LeaseRepair repair) {
        // The charge is an id here: its amount and payer are the installment
        // row's columns, and that row has its own door.
        LeaseBreakdown charge = breakdownRepository
                .findByEntityCodeAndReferenceId(Entities.LEASE_REPAIRS, repair.getId())
                .orElse(null);
        return toResponse(repair, charge != null ? charge.getId() : null);
    }

    private LeaseRepairResponse toResponse(LeaseRepair repair, UUID chargeBreakdownId) {
        return stateMapper.toResponse(repair, chargeBreakdownId);
    }

    private Lease requireLease(UUID leaseId) {
        return leaseRepository.findById(leaseId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.LEASE_NOT_FOUND,
                        "Lease not found: " + leaseId));
    }

    private String statusOf(Lease lease) {
        return lease.getStatus() != null ? lease.getStatus().getCode() : null;
    }

    private void auditRepair(String activityCode, LeaseRepair repair, String operation, Map<String, String> data) {
        auditTrail.record(Entities.LEASE_REPAIRS, activityCode, repair.getId(), operation, null, data);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // The dispute ticket
    // ═══════════════════════════════════════════════════════════════════════

    /** Freeze the machine: a ticket now owns every forced decision. */
    @Transactional
    public void open(UUID leaseId, UUID callerId, String reason) {
        Lease lease = requireLease(leaseId);
        requireParty(lease, callerId);
        String status = statusOf(lease);
        if (NotraiusStatuses.LEASE_DISPUTED.equals(status)) {
            throw new ConflictException(NotraiusErrors.LEASE_DISPUTE_INVALID,
                    "Lease " + leaseId + " is already DISPUTED");
        }
        if (NotraiusStatuses.LEASE_COMPLETED.equals(status)
                || NotraiusStatuses.LEASE_CANCELLED.equals(status)
                || NotraiusStatuses.LEASE_EXPIRED.equals(status)) {
            throw new ConflictException(NotraiusErrors.LEASE_DISPUTE_INVALID,
                    "Lease " + leaseId + " is " + status + "; there is nothing live to freeze");
        }
        lease.setStatus(statusResolver.resolve(Entities.LEASES, NotraiusStatuses.LEASE_DISPUTED));
        lease.setVersion(lease.getVersion() + 1);
        leaseRepository.save(lease);
        auditDispute(NotraiusActivities.Leases.DISPUTE, lease, "DISPUTE", Map.of(
                "leaseId", leaseId.toString(),
                "reason", reason));
        log.info("Lease {} DISPUTED by {}: {}", leaseId, callerId, reason);
    }

    /**
     * Unfreeze and recalculate: restore the live word, re-derive the calendar,
     * decide the lapsed misses from the record, and end a tenancy whose validity
     * already passed.
     */
    @Transactional
    public void resolve(UUID leaseId, UUID callerId, String note) {
        Lease lease = requireLease(leaseId);
        // A dispute's resolution is the STAFF's decision (the endpoint declares
        // ADMIN) — it must not require the operator to be a party to the lease,
        // which would make the door unreachable for every operator who is not.
        // The ticket itself is still the parties' to open (open() below).
        if (!NotraiusStatuses.LEASE_DISPUTED.equals(statusOf(lease))) {
            throw new ConflictException(NotraiusErrors.LEASE_DISPUTE_INVALID,
                    "Lease " + leaseId + " is " + statusOf(lease) + ", not DISPUTED");
        }

        boolean validityEnded = lease.getValidity() != null
                && lease.getValidity().getToDate() != null
                && !lease.getValidity().getToDate().isAfter(Instant.now());
        lease.setStatus(statusResolver.resolve(Entities.LEASES, NotraiusStatuses.LEASE_ACTIVE));
        lease.setVersion(lease.getVersion() + 1);
        leaseRepository.save(lease);

        agreementService.sync(lease);
        int acted = financeService.recalc(leaseId);
        reopenForResolution(leaseId);
        if (validityEnded) {
            closeIfDue(leaseId);
        }

        auditDispute(NotraiusActivities.Leases.RESOLVE, lease, "RESOLVE", Map.of(
                "leaseId", leaseId.toString(),
                "note", note != null ? note : "",
                "lapsedRowsAdjudicated", String.valueOf(acted)));
        log.info("Lease {} resolved by {}: {} lapsed row(s) adjudicated, validityEnded={}",
                leaseId, callerId, acted, validityEnded);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // OwnershipGuard — the parties to the lease
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * args[0]: the lease, args[1]: the caller id. The tenant and the landlord
     * are the two parties of a lease; a recipient of money is neither (6.9).
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

    private void requireParty(Lease lease, UUID callerId) {
        requireParty("Only a party to lease " + lease.getId() + " may move its dispute",
                NotraiusErrors.LEASE_PARTY_FORBIDDEN, lease, callerId);
    }

    /**
     * A party-level door: the tenant and the landlord pass directly, a seat or
     * app delegation passes through the estate's ownership guard (which re-asks
     * the domain question as the grantor), and everyone else is refused with
     * the module's own error code rather than the guard's generic one.
     */
    private void requireParty(String message, ErrorCode error, Lease lease, UUID callerId) {
        if (callerId != null
                && (callerId.equals(lease.getUserId()) || callerId.equals(lease.getOwnerId()))) {
            return;
        }
        try {
            requireOwnership(message, lease, callerId);
        } catch (ForbiddenException e) {
            throw new ForbiddenException(error, message);
        }
    }

    private void auditDispute(String activityCode, Lease lease, String operation, Map<String, String> data) {
        auditTrail.record(Entities.LEASES, activityCode, lease.getId(), operation, null, data);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // The vigil
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * One full pass. Every unit of work owns its transaction and its failure.
     * <p>
     * <b>Six worklists, six cursors, one law.</b> The notice pass, the bills, the
     * overdue settlements, the ended tenancies, the rolling horizons and the
     * reconversions each read a bounded keyset page and act on it row by row
     * ({@link SweepPager}); a worklist bigger than the row budget resumes exactly
     * after its last settled row on the next tick, and a worklist that was
     * exhausted starts over from the head so nothing that became eligible behind
     * the cursor is stranded.
     * <p>
     * The pass owns no transaction of its own: a crashed step rolls back alone and
     * a step that dies mid-write rolls back whole rather than half-applied.
     */
    public void tick() {
        Instant now = Instant.now();
        List<String> liveLeases = List.of(NotraiusStatuses.LEASE_ACTIVE);

        int escalated = escalateLapsed();

        int judged = sweeps.run(SweepJobs.LEASE_VIGIL_BILLS,
                (afterKey, afterId, limit) -> breakdownRepository.findDuePage(
                        List.of(NotraiusStatuses.LEASE_BREAKDOWN_PENDING), now, afterKey, afterId,
                        PageRequest.of(0, limit)),
                billId -> financeService.resolve(billId) ? 1 : 0);

        int overdue = sweeps.run(SweepJobs.LEASE_VIGIL_SETTLEMENTS,
                (afterKey, afterId, limit) -> settlementRepository.findLapsedPage(
                        NotraiusStatuses.LEASE_SETTLEMENT_OPEN, now, liveLeases, afterKey, afterId,
                        PageRequest.of(0, limit)),
                settlementId -> {
                    markOverdue(settlementId);
                    return 1;
                });

        int ended = sweeps.run(SweepJobs.LEASE_VIGIL_ENDINGS,
                (afterKey, afterId, limit) -> leaseRepository.findEndedActivePage(
                        NotraiusStatuses.LEASE_ACTIVE, now, afterKey, afterId,
                        PageRequest.of(0, limit)),
                leaseId -> {
                    closeIfDue(leaseId);
                    return 1;
                });

        int extended = sweeps.run(SweepJobs.LEASE_VIGIL_HORIZONS,
                (afterKey, afterId, limit) -> leaseRepository.findOpenEndedActivePage(
                        NotraiusStatuses.LEASE_ACTIVE, afterKey, afterId, PageRequest.of(0, limit)),
                agreementService::syncById);

        int cleared = agreementService.clearClearedReconversions();

        if (escalated + judged + overdue + ended + extended + cleared > 0) {
            log.info("Lease vigil: escalated={}, lapsedRows={}, settlementsOverdue={}, ended={}, "
                            + "horizonRows={}, reconversionsCleared={}",
                    escalated, judged, overdue, ended, extended, cleared);
        }
    }

}