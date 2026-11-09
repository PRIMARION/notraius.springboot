package com.notraius.common.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.notraius.common.audit.AuditTrail;
import com.notraius.common.integration.PayPaymentClient;
import com.notraius.database.entities.reference.Entities;
import com.notraius.database.entities.visit.Visit;
import com.notraius.database.repositories.visit.VisitRepository;
import com.notraius.modules.core.common.constants.NotraiusActivities;
import com.notraius.modules.core.common.constants.NotraiusStatuses;
import com.notraius.modules.visit.visits.dto.response.VisitResponse;
import com.notraius.modules.visit.visits.mapper.VisitMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * The one place the visit's money and terminal words are spoken.
 * <p>
 * Four acts recur across every sub-module — refund the custody in full, release
 * the split to its born destination, expire, complete — and each of them is a
 * money movement plus a state plus an audit entry that must never disagree. The
 * services call them from one place so a later change (another status word, a
 * different audit shape) cannot leave one caller behind.
 * <p>
 * <b>Both money calls fail closed.</b> A refund or a release that the payment
 * host refuses throws, the transaction rolls back, and the visit keeps the state
 * it had — a visit whose money did not move must never claim it did.
 */
@Service
@Slf4j
public class VisitLedgerService {

    private final VisitRepository visitRepository;
    private final PayPaymentClient payPaymentClient;
    private final StatusResolver statusResolver;
    private final VisitMapper visitMapper;
    private final AuditTrail auditTrail;

    public VisitLedgerService(VisitRepository visitRepository,
                              PayPaymentClient payPaymentClient,
                              StatusResolver statusResolver,
                              VisitMapper visitMapper,
                              AuditTrail auditTrail) {
        this.visitRepository = visitRepository;
        this.payPaymentClient = payPaymentClient;
        this.statusResolver = statusResolver;
        this.visitMapper = visitMapper;
        this.auditTrail = auditTrail;
    }

    /** Hand the whole custody back to the visitor — the module's only refund shape. */
    public void refundInFull(Visit visit, String reason) {
        if (visit.getPaymentId() == null) {
            log.warn("Visit {} has no known payment to refund ({})", visit.getId(), reason);
            return;
        }
        payPaymentClient.refund(visit.getPaymentId());
        log.info("Visit {} refunded in full ({})", visit.getId(), reason);
    }

    /**
     * Convey the held split to the destination it was born with — the property
     * owner named at funding, never a lookup performed now.
     */
    public void releaseToOwner(Visit visit) {
        if (visit.getPaymentId() == null || visit.getPaymentSplitId() == null) {
            throw new IllegalStateException(
                    "Visit " + visit.getId() + " cannot settle: payment or split reference is missing");
        }
        payPaymentClient.releaseSplit(visit.getPaymentId(), visit.getPaymentSplitId());
        log.info("Visit {} settled: split {} released to its born destination", visit.getId(),
                visit.getPaymentSplitId());
    }

    /** Move the visit to a terminal state and record it, with the reason as the operation. */
    public VisitResponse moveTo(Visit visit, String statusCode, String activityCode, String operation) {
        VisitResponse before = visitMapper.toResponse(visit);
        visit.setStatus(statusResolver.resolve(Entities.VISITS, statusCode));
        visit.setVersion(visit.getVersion() + 1);
        visitRepository.save(visit);
        VisitResponse after = visitMapper.toResponse(visit);
        audit(activityCode, visit.getId(), operation, after, before);
        return after;
    }

    /** EXPIRED: the clock's word — nothing happened, so the money goes home. */
    public VisitResponse expire(Visit visit, String reason) {
        return moveTo(visit, NotraiusStatuses.VISIT_EXPIRED,
                NotraiusActivities.Visits.EXPIRE, truncate(reason));
    }

    /** COMPLETED: the visit happened; the money has already moved (or moves first). */
    public VisitResponse complete(Visit visit, String operation) {
        visit.setCompletedAt(Instant.now());
        return moveTo(visit, NotraiusStatuses.VISIT_COMPLETED,
                NotraiusActivities.Visits.COMPLETE, operation);
    }

    public void audit(String activityCode, UUID referenceId, String operation, Object after, Object before) {
        // Audit never rolls the business act back; AuditTrail logs the failure.
        auditTrail.record(Entities.VISITS, activityCode, referenceId, operation, before, after);
    }

    private String truncate(String reason) {
        return reason != null && reason.length() > 100 ? reason.substring(0, 100) : reason;
    }
}
