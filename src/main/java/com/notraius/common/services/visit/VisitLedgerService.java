package com.notraius.common.services.visit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.notraius.common.audit.AuditTrail;
import com.notraius.common.integration.pay.PayPaymentClient;
import com.notraius.database.entities.lease.reference.amenity.Entities;
import com.notraius.database.entities.lease.visit.Visit;
import com.notraius.database.repositories.lease.visit.VisitRepository;
import com.notraius.modules.core.core.common.constants.NotraiusActivities;
import com.notraius.modules.core.core.common.constants.NotraiusStatuses;
import com.notraius.modules.lease.visit.visits.dto.response.VisitResponse;
import com.notraius.modules.lease.visit.visits.VisitMapper;
import lombok.extern.slf4j.Slf4j;
import org.primarion.module.communication.communication.communication.notification.service.NotificationService;
import org.primarion.module.communication.infra.anomaly.webhook.service.WebhookDispatchHelper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import com.notraius.common.services.logs.StatusResolver;
import java.util.Locale;

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
    private final WebhookDispatchHelper webhookDispatchHelper;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public VisitLedgerService(VisitRepository visitRepository,
                              PayPaymentClient payPaymentClient,
                              StatusResolver statusResolver,
                              VisitMapper visitMapper,
                              AuditTrail auditTrail,
                              WebhookDispatchHelper webhookDispatchHelper,
                              NotificationService notificationService,
                              ObjectMapper objectMapper) {
        this.visitRepository = visitRepository;
        this.payPaymentClient = payPaymentClient;
        this.statusResolver = statusResolver;
        this.visitMapper = visitMapper;
        this.auditTrail = auditTrail;
        this.webhookDispatchHelper = webhookDispatchHelper;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
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
        announceTerminal(visit, statusCode, operation);
        return after;
    }

    /**
     * A terminal word is public fact: the money and the state moved for every
     * party at once, so every party with a webhook registration hears it and
     * every party gets the in-app notice (the ledger does not know who acted —
     * cancel, complete, sweep and staff resolution all arrive here). Best-effort
     * by contract: dispatch is fail-soft inside the helper, and a refused
     * notice must never roll back the settlement it accompanies.
     */
    private void announceTerminal(Visit visit, String statusCode, String operation) {
        if (webhookDispatchHelper == null) {
            return;
        }
        LinkedHashSet<UUID> parties = new LinkedHashSet<>();
        if (visit.getVisitorId() != null) {
            parties.add(visit.getVisitorId());
        }
        if (visit.getOwnerId() != null) {
            parties.add(visit.getOwnerId());
        }
        if (visit.getUserId() != null) {
            parties.add(visit.getUserId());
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("event", statusCode);
        payload.put("visitId", visit.getId().toString());
        payload.put("operation", operation);
        payload.put("status", statusCode);
        if (visit.getVisitorId() != null) {
            payload.put("visitorId", visit.getVisitorId().toString());
        }
        if (visit.getOwnerId() != null) {
            payload.put("ownerId", visit.getOwnerId().toString());
        }
        if (visit.getUserId() != null) {
            payload.put("assignedUserId", visit.getUserId().toString());
        }
        payload.put("amount", visit.getAmount() != null ? visit.getAmount().toPlainString() : null);
        webhookDispatchHelper.dispatchToUsers(List.copyOf(parties),
                NotraiusActivities.VISITS, statusCode, payload);

        String title;
        String message;
        switch (statusCode) {
            case NotraiusStatuses.VISIT_COMPLETED ->
                    message = "Visit " + visit.getId() + " is completed and the held money settled.";
            case NotraiusStatuses.VISIT_EXPIRED ->
                    message = "Visit " + visit.getId() + " expired before it could happen; the held money was returned.";
            default -> message = "Visit " + visit.getId() + " was " + statusCode + " (" + operation + ").";
        }
        title = "Visit " + statusCode.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        for (UUID party : parties) {
            try {
                notificationService.createNotification(party, title, message, Entities.VISITS, null);
            } catch (RuntimeException e) {
                log.warn("Visit {} notice deferred for user {}: {}", visit.getId(), party, e.getMessage());
            }
        }
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
