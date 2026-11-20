package com.notraius.modules.lease.visit.visits.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.notraius.common.audit.AuditTrail;
import com.notraius.common.NotraiusErrors;
import com.notraius.common.integration.pay.PayPaymentClient;
import com.notraius.common.integration.pay.ReputationAssessmentClient;
import com.notraius.common.services.logs.StatusResolver;
import com.notraius.common.services.visit.VisitLedgerService;
import com.notraius.common.services.visit.VisitTurnReader;
import com.notraius.config.notraius.properties.NotraiusProperties;
import com.notraius.database.entities.asset.asset.Property;
import com.notraius.database.entities.lease.reference.amenity.Entities;
import com.notraius.database.entities.lease.reference.amenity.Validity;
import com.notraius.database.entities.lease.visit.Visit;
import com.notraius.database.entities.lease.visit.VisitPricing;
import com.notraius.database.repositories.lease.LogsRepository;
import com.notraius.database.repositories.lease.reference.communication.ValidityRepository;
import com.notraius.database.repositories.lease.visit.VisitPricingRepository;
import com.notraius.database.repositories.lease.visit.VisitRepository;
import com.notraius.modules.core.core.common.constants.NotraiusActivities;
import com.notraius.modules.core.core.common.constants.NotraiusStatuses;
import com.notraius.modules.core.core.common.schedule.sweep.SweepJobs;
import com.notraius.modules.core.core.common.schedule.sweep.SweepPager;
import com.notraius.modules.lease.visit.visits.dto.request.visit.VisitAssessmentCreateRequest;
import com.notraius.modules.lease.visit.visits.dto.request.visit.VisitAssignRequest;
import com.notraius.modules.lease.visit.visits.dto.request.visit.VisitRequestCreateRequest;
import com.notraius.modules.lease.visit.visits.dto.request.visit.VisitRescheduleRequest;
import com.notraius.modules.lease.visit.visits.dto.request.visit.VisitResolutionRequest;
import com.notraius.modules.lease.visit.visits.dto.request.VisitTimeProposalRequest;
import com.notraius.modules.lease.visit.visits.dto.response.VisitAssessmentResponse;
import com.notraius.modules.lease.visit.visits.dto.response.VisitResponse;
import com.notraius.modules.lease.visit.visits.VisitMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.primarion.module.app.access.consent.database.Activity;
import org.primarion.module.app.access.consent.database.ActivityRepository;
import org.primarion.module.app.access.consent.consent.service.subscription.OwnershipGuard;
import org.primarion.module.communication.communication.communication.support.service.ReferenceParties;
import org.primarion.module.communication.communication.communication.notification.service.NotificationService;
import org.primarion.module.communication.errors.client.BadRequestException;
import org.primarion.module.communication.errors.client.ConflictException;
import org.primarion.module.communication.errors.client.ForbiddenException;
import org.primarion.module.communication.errors.client.NotFoundException;
import org.primarion.module.communication.infra.anomaly.webhook.service.WebhookDispatchHelper;
import org.primarion.module.utils.utils.security.security.users.user.UserClient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientResponseException;
import com.notraius.modules.lease.visit.visits.service.VisitNextActions;

/**
 * The visits sub-module's single service (PLAN.md 5.1–5.5).
 * <p>
 * One file holds the whole tenancy of a visit: the funded request, acceptance
 * and planning, execution and its judgments, the outcome sweeps, the two ways
 * out, staff's resolution and the property's own wind-down. The stage sections
 * below preserve the transactional boundaries and the laws each act enforces;
 * they are sections of one service, not separate beans.
 */
@Service
@Slf4j
public class VisitService extends OwnershipGuard implements ReferenceParties {

    private final VisitRepository visitRepository;
    private final PayPaymentClient payPaymentClient;
    private final StatusResolver statusResolver;
    private final VisitMapper visitMapper;
    private final UserClient userClient;
    private final AuditTrail auditTrail;
    private final ReputationAssessmentClient assessmentClient;
    private final ActivityRepository activityRepository;
    private final NotraiusProperties properties;
    private final VisitLedgerService visitLedgerService;
    private final VisitTurnReader visitTurnReader;
    private final VisitPricingRepository visitPricingRepository;
    private final ValidityRepository validityRepository;
    private final LogsRepository logsRepository;
    private final SweepPager sweeps;
    private final WebhookDispatchHelper webhookDispatchHelper;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public VisitService(VisitRepository visitRepository,
                                 PayPaymentClient payPaymentClient,
                                 StatusResolver statusResolver,
                                 VisitMapper visitMapper,
                                 UserClient userClient,
                                 AuditTrail auditTrail,
                                 ReputationAssessmentClient assessmentClient,
                                 ActivityRepository activityRepository,
                                 NotraiusProperties properties,
                                 VisitLedgerService visitLedgerService,
                                 VisitTurnReader visitTurnReader,
                                 VisitPricingRepository visitPricingRepository,
                                 ValidityRepository validityRepository,
                                 LogsRepository logsRepository,
                                 SweepPager sweeps,
                                 WebhookDispatchHelper webhookDispatchHelper,
                                 NotificationService notificationService,
                                 ObjectMapper objectMapper) {
        this.visitRepository = visitRepository;
        this.payPaymentClient = payPaymentClient;
        this.statusResolver = statusResolver;
        this.visitMapper = visitMapper;
        this.userClient = userClient;
        this.auditTrail = auditTrail;
        this.assessmentClient = assessmentClient;
        this.activityRepository = activityRepository;
        this.properties = properties;
        this.visitLedgerService = visitLedgerService;
        this.visitTurnReader = visitTurnReader;
        this.visitPricingRepository = visitPricingRepository;
        this.validityRepository = validityRepository;
        this.logsRepository = logsRepository;
        this.sweeps = sweeps;
        this.webhookDispatchHelper = webhookDispatchHelper;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    // ═══════════════════════════════════════════════════════════════════
    // VisitAcceptanceService
    // ═══════════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════════════
    // The stamp
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Stamp a user id onto a paid request. Legal from REQUESTED (first stamp) and
     * from ASSIGNED (re-stamp, the operator's colleagues case); illegal once
     * acceptance has landed.
     */
    @Transactional
    public VisitResponse assign(UUID visitId, VisitAssignRequest request, UUID callerId) {
        Visit visit = requireVisit(visitId);
        acceptanceRequireOwner(visit, callerId);
        acceptanceRequireStatusIn(visit, "assign", NotraiusStatuses.VISIT_REQUESTED, NotraiusStatuses.VISIT_ASSIGNED);

        UUID assigneeId = request.getAssignedUserId();
        acceptanceRequireDistinctPerson(visit, assigneeId);
        acceptanceRequireExistingUser(assigneeId);

        boolean restamp = NotraiusStatuses.VISIT_ASSIGNED.equals(statusCodeOf(visit))
                && !Objects.equals(visit.getUserId(), assigneeId);
        if (Objects.equals(visit.getUserId(), assigneeId)
                && NotraiusStatuses.VISIT_ASSIGNED.equals(statusCodeOf(visit))) {
            // The same person, already stamped: nothing to do, nothing to log.
            return visitMapper.toResponse(visit);
        }

        VisitResponse before = visitMapper.toResponse(visit);
        visit.setUserId(assigneeId);
        visit.setStatus(statusResolver.resolve(Entities.VISITS, NotraiusStatuses.VISIT_ASSIGNED));
        visit.setVersion(visit.getVersion() + 1);
        visitRepository.save(visit);

        VisitResponse after = visitMapper.toResponse(visit);
        audit(NotraiusActivities.Visits.ASSIGN, visitId, restamp ? "REASSIGN" : "ASSIGN", after, before);
        announceToParties(visit, restamp ? "REASSIGNED" : "ASSIGNED", callerId);
        safeNotify(assigneeId, "Visit assigned to you",
                "You were assigned to visit " + visitId + "; accept to begin planning.");
        log.info("Visit {} {} to user={} by owner={}", visitId, restamp ? "reassigned" : "assigned",
                assigneeId, callerId);
        return after;
    }

    /** The stamped human accepts: ASSIGNED → PLANNING, and the plan may begin. */
    @Transactional
    public VisitResponse accept(UUID visitId, UUID callerId) {
        Visit visit = requireVisit(visitId);
        acceptanceRequireStatusIn(visit, "accept", NotraiusStatuses.VISIT_ASSIGNED);
        acceptanceRequireAssignee(visit, callerId);

        VisitResponse before = visitMapper.toResponse(visit);
        visit.setStatus(statusResolver.resolve(Entities.VISITS, NotraiusStatuses.VISIT_PLANNING));
        visit.setVersion(visit.getVersion() + 1);
        visitRepository.save(visit);

        VisitResponse after = visitMapper.toResponse(visit);
        audit(NotraiusActivities.Visits.ACCEPT, visitId, "ACCEPT", after, before);
        announceToParties(visit, "ACCEPTED", callerId);
        notifyPartiesExcept(visit, callerId, "Visit accepted",
                "Visit " + visitId + " was accepted; planning has begun.");
        log.info("Visit {} accepted by assignee={}; planning begins", visitId, callerId);
        return after;
    }

    /** The stamped human declines the responsibility: back to REQUESTED, the stamp undone. */
    @Transactional
    public VisitResponse reject(UUID visitId, UUID callerId) {
        Visit visit = requireVisit(visitId);
        acceptanceRequireStatusIn(visit, "reject", NotraiusStatuses.VISIT_ASSIGNED);
        acceptanceRequireAssignee(visit, callerId);

        VisitResponse before = visitMapper.toResponse(visit);
        visit.setUserId(null);
        visit.setStatus(statusResolver.resolve(Entities.VISITS, NotraiusStatuses.VISIT_REQUESTED));
        visit.setVersion(visit.getVersion() + 1);
        visitRepository.save(visit);

        VisitResponse after = visitMapper.toResponse(visit);
        audit(NotraiusActivities.Visits.REJECT, visitId, "REJECT", after, before);
        announceToParties(visit, "REJECTED", callerId);
        notifyPartiesExcept(visit, callerId, "Visit assignment rejected",
                "Visit " + visitId + " is unassigned again and needs another human.");
        log.info("Visit {} rejected by assignee={}; the request is live again", visitId, callerId);
        return after;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // The owner's decline — full refund, no fee
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * The owner declines a paid request before anybody accepted it. Money held
     * returns to the visitor <b>in full</b>: the 20% fee exists only once a visit
     * is confirmed and committed to, and nothing was.
     */
    @Transactional
    public VisitResponse decline(UUID visitId, UUID callerId) {
        Visit visit = requireVisit(visitId);
        acceptanceRequireOwner(visit, callerId);
        acceptanceRequireStatusIn(visit, "decline", NotraiusStatuses.VISIT_REQUESTED, NotraiusStatuses.VISIT_ASSIGNED);

        VisitResponse before = visitMapper.toResponse(visit);
        if (visit.getPaymentId() != null) {
            // Fail closed: if pay will not take the refund back, the visit stays
            // where it is — an exception rolls the status change back with it.
            payPaymentClient.refund(visit.getPaymentId());
        } else {
            log.warn("Visit {} has no known payment; declining without a refund call", visitId);
        }
        visit.setUserId(null);
        visit.setStatus(statusResolver.resolve(Entities.VISITS, NotraiusStatuses.VISIT_CANCELLED));
        visit.setVersion(visit.getVersion() + 1);
        visitRepository.save(visit);

        VisitResponse after = visitMapper.toResponse(visit);
        audit(NotraiusActivities.Visits.DECLINE, visitId, "DECLINE", after, before);
        announceToParties(visit, "DECLINED", callerId);
        notifyPartiesExcept(visit, callerId, "Visit declined",
                "The owner declined visit " + visitId + "; the held money was returned in full.");
        log.info("Visit {} declined by owner={}; the visitor is refunded in full", visitId, callerId);
        return after;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Gates
    // ═══════════════════════════════════════════════════════════════════════

    private Visit requireVisit(UUID visitId) {
        return visitRepository.findById(visitId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.VISIT_NOT_FOUND,
                        "Visit not found: " + visitId));
    }

    private void acceptanceRequireOwner(Visit visit, UUID callerId) {
        if (!Objects.equals(visit.getOwnerId(), callerId)) {
            throw new ForbiddenException(NotraiusErrors.VISIT_SCHEDULE_FORBIDDEN,
                    "Only the property's owner may perform this act on visit " + visit.getId());
        }
    }

    private void acceptanceRequireAssignee(Visit visit, UUID callerId) {
        if (!Objects.equals(visit.getUserId(), callerId)) {
            throw new ForbiddenException(NotraiusErrors.VISIT_ASSIGNEE_MISMATCH,
                    "Visit " + visit.getId() + " is assigned to another user");
        }
    }

    /** The stamp names someone else: not the visitor, not the owner, never null. */
    private void acceptanceRequireDistinctPerson(Visit visit, UUID assigneeId) {
        if (assigneeId == null
                || assigneeId.equals(visit.getVisitorId())
                || assigneeId.equals(visit.getOwnerId())) {
            throw new BadRequestException(NotraiusErrors.VISIT_ASSIGNEE_INVALID,
                    "User " + assigneeId + " cannot be assigned to visit " + visit.getId());
        }
    }

    /** Identity is the authority on who exists; unreachable is a refusal, not a pass. */
    private void acceptanceRequireExistingUser(UUID userId) {
        UserClient.Resolution resolution = userClient.resolveById(userId);
        if (!resolution.identityReachable()) {
            throw new ForbiddenException(NotraiusErrors.VISIT_ASSIGNEE_UNREACHABLE,
                    "Identity could not be reached to verify user " + userId);
        }
        if (resolution.user().isEmpty()) {
            throw new BadRequestException(NotraiusErrors.VISIT_ASSIGNEE_INVALID,
                    "No such user: " + userId);
        }
    }

    private void acceptanceRequireStatusIn(Visit visit, String operation, String... allowed) {
        String actual = statusCodeOf(visit);
        for (String status : allowed) {
            if (status.equals(actual)) {
                return;
            }
        }
        throw new ConflictException(NotraiusErrors.VISIT_INVALID_STATE,
                "Visit " + visit.getId() + " is " + actual + "; " + operation
                        + " requires one of " + String.join(", ", allowed));
    }

    private String statusCodeOf(Visit visit) {
        return visit.getStatus() != null ? visit.getStatus().getCode() : null;
    }

    private void audit(String activityCode, UUID referenceId, String operation, Object after, Object before) {
        auditTrail.record(Entities.VISITS, activityCode, referenceId, operation, before, after);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Announcing (webhooks + in-app notices)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * The parties a visit move can matter to: visitor, property owner, and the
     * assignee once stamped. The actor is excluded by the callers — the person
     * who did the act already knows it happened.
     */
    private List<UUID> visitPartiesExcept(Visit visit, UUID actorId) {
        LinkedHashSet<UUID> parties = new LinkedHashSet<>();
        if (visit.getVisitorId() != null && !visit.getVisitorId().equals(actorId)) {
            parties.add(visit.getVisitorId());
        }
        if (visit.getOwnerId() != null && !visit.getOwnerId().equals(actorId)) {
            parties.add(visit.getOwnerId());
        }
        if (visit.getUserId() != null && !visit.getUserId().equals(actorId)) {
            parties.add(visit.getUserId());
        }
        return List.copyOf(parties);
    }

    /** The verifiable facts every visit webhook carries — ids, money, window, state. */
    private ObjectNode visitPayload(Visit visit, String event) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("event", event);
        payload.put("visitId", visit.getId().toString());
        payload.put("status", statusCodeOf(visit));
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
        if (visit.getValidity() != null && visit.getValidity().getFromDate() != null) {
            payload.put("fromDate", visit.getValidity().getFromDate().toString());
        }
        if (visit.getValidity() != null && visit.getValidity().getToDate() != null) {
            payload.put("toDate", visit.getValidity().getToDate().toString());
        }
        return payload;
    }

    /** Webhooks to every party except the actor, under the visit family. */
    private void announceToParties(Visit visit, String event, UUID actorId) {
        if (webhookDispatchHelper == null) {
            return;
        }
        webhookDispatchHelper.dispatchToUsers(visitPartiesExcept(visit, actorId),
                NotraiusActivities.VISITS, event, visitPayload(visit, event));
    }

    /**
     * In-app notice, best-effort by contract: a broken identity or a refusing
     * preference store must never roll back the business act it accompanies
     * (the same rule the event module's notification binding states). The
     * reference entity tags the notice {@code VISITS} without a reference id —
     * several distinct notices fire per visit over its life, and one id would
     * let the notification dedupe swallow all but the first.
     */
    private void safeNotify(UUID userId, String title, String message) {
        if (userId == null || notificationService == null) {
            return;
        }
        try {
            notificationService.createNotification(userId, title, message,
                    Entities.VISITS, null);
        } catch (RuntimeException e) {
            log.warn("Visit notice deferred for user {}: {}", userId, e.getMessage());
        }
    }

    /**
     * The in-app half of an announcement: every party but the actor hears the
     * same sentence. Uses the same best-effort contract as {@link #safeNotify}.
     */
    private void notifyPartiesExcept(Visit visit, UUID actorId, String title, String message) {
        for (UUID party : visitPartiesExcept(visit, actorId)) {
            safeNotify(party, title, message);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // VisitAssessmentService
    // ═══════════════════════════════════════════════════════════════════

    /** File this caller's judgment of the counterpart. One per party per visit. */
    @Transactional
    public VisitAssessmentResponse assess(UUID visitId, VisitAssessmentCreateRequest request,
                                          UUID callerId) {
        Visit visit = requireVisit(visitId);
        assessmentRequireStarted(visit, callerId);
        assessmentRequireInsideWindow(visit);

        UUID targetUserId = assessmentTargetOf(visit, callerId);
        if (Objects.equals(targetUserId, callerId)) {
            // Defensive: the property owner visiting their own listing is refused
            // at request time, so this shape should not exist.
            throw new BadRequestException(NotraiusErrors.VISIT_ASSESSMENT_FORBIDDEN,
                    "You cannot assess yourself");
        }

        boolean alreadyAssessed = assessmentClient.listForVisit(visitId).stream()
                .anyMatch(a -> Objects.equals(a.createdByUserId(), callerId));
        if (alreadyAssessed) {
            throw new ConflictException(NotraiusErrors.VISIT_ALREADY_ASSESSED,
                    "You have already assessed visit " + visitId);
        }

        UUID activityId = activityRepository.findByCode(NotraiusActivities.Visits.ASSESS)
                .map(Activity::getId)
                .orElseThrow(() -> new IllegalStateException(
                        "Activity not seeded: " + NotraiusActivities.Visits.ASSESS));
        ReputationAssessmentClient.AssessmentRef created = assessmentClient.submit(
                targetUserId, callerId, visitId, NotraiusActivities.Visits.ASSESS, activityId,
                request.getReason(), request.getAxisScores());
        if (created == null) {
            throw new IllegalStateException(
                    "The reputation host returned no assessment for visit " + visitId);
        }
        log.info("Visit {} assessed by user={} against user={} (assessment {})",
                visitId, callerId, targetUserId, created.id());
        return assessmentToResponse(created);
    }

    /** Every judgment this visit has collected — the gate's own source of truth. */
    @Transactional(readOnly = true)
    public List<VisitAssessmentResponse> assessments(UUID visitId, UUID callerId) {
        Visit visit = requireVisit(visitId);
        if (!assessmentIsParty(visit, callerId)) {
            throw new ForbiddenException(NotraiusErrors.VISIT_ASSESSMENT_FORBIDDEN,
                    "Only the parties to visit " + visitId + " may read its assessments");
        }
        return assessmentClient.listForVisit(visitId).stream().map(this::assessmentToResponse).toList();
    }

    /**
     * The confirm-visit gate: both sides have judged. Derived from the host's
     * list every time — never from a flag that could survive a revocation.
     */
    @Transactional(readOnly = true)
    public boolean bothAssessed(UUID visitId, Visit visit) {
        List<ReputationAssessmentClient.AssessmentRef> filed = assessmentClient.listForVisit(visitId);
        boolean visitorAssessed = filed.stream()
                .anyMatch(a -> Objects.equals(a.createdByUserId(), visit.getVisitorId()));
        boolean ownerSideAssessed = filed.stream()
                .anyMatch(a -> Objects.equals(a.createdByUserId(), visit.getUserId())
                        || Objects.equals(a.createdByUserId(), visit.getOwnerId()));
        return visitorAssessed && ownerSideAssessed;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Gates
    // ═══════════════════════════════════════════════════════════════════════

    private void assessmentRequireStarted(Visit visit, UUID callerId) {
        if (!assessmentIsParty(visit, callerId)) {
            throw new ForbiddenException(NotraiusErrors.VISIT_ASSESSMENT_FORBIDDEN,
                    "Only the parties to visit " + visit.getId() + " may assess it");
        }
        String status = visit.getStatus() != null ? visit.getStatus().getCode() : null;
        if (!NotraiusStatuses.VISIT_STARTED.equals(status) || visit.getStartedAt() == null) {
            throw new ConflictException(NotraiusErrors.VISIT_NOT_STARTED,
                    "Visit " + visit.getId() + " is " + status
                            + "; assessment begins once the visit has started");
        }
    }

    private void assessmentRequireInsideWindow(Visit visit) {
        Instant now = Instant.now();
        Instant from = visit.getValidity().getFromDate();
        Instant to = visit.getValidity().getToDate();
        Instant closes = to != null
                ? to.plus(properties.getVisitAssessmentWindowMinutes(), ChronoUnit.MINUTES)
                : null;
        if (closes == null || now.isAfter(closes)
                || (from != null && now.isBefore(from))) {
            throw new ConflictException(NotraiusErrors.VISIT_ASSESSMENT_WINDOW_CLOSED,
                    "Visit " + visit.getId() + " accepts assessments between the start mark and "
                            + closes);
        }
    }

    private boolean assessmentIsParty(Visit visit, UUID callerId) {
        return callerId != null && (callerId.equals(visit.getVisitorId())
                || callerId.equals(visit.getUserId())
                || callerId.equals(visit.getOwnerId()));
    }

    /** The rule of who judges whom, resolved from the visit alone. */
    private UUID assessmentTargetOf(Visit visit, UUID callerId) {
        if (callerId.equals(visit.getVisitorId())) {
            return visit.getOwnerId();
        }
        return visit.getVisitorId();
    }

    private VisitAssessmentResponse assessmentToResponse(ReputationAssessmentClient.AssessmentRef ref) {
        return new VisitAssessmentResponse(ref.id(), ref.createdByUserId(), ref.reason(),
                ref.axisScores(), ref.score(), ref.createdAt());
    }

    // ═══════════════════════════════════════════════════════════════════
    // VisitCancellationService
    // ═══════════════════════════════════════════════════════════════════

    /** Before the commitment: the free door. CONFIRMED: the paid door. */
    private static final List<String> CANCELLABLE = List.of(
            NotraiusStatuses.VISIT_REQUESTED,
            NotraiusStatuses.VISIT_ASSIGNED,
            NotraiusStatuses.VISIT_PLANNING,
            NotraiusStatuses.VISIT_CONFIRMED);

    /** The penalty share of the visit fee on a confirmed cancellation (PLAN 5.4). */
    private static final BigDecimal CANCELLATION_PENALTY_RATE = new BigDecimal("0.20");

    private static final int MONEY_SCALE = 2;

    /** The visitor walks away. Free before CONFIRMED; refund plus 20% and a bad review after. */
    @Transactional
    public VisitResponse cancel(UUID visitId, UUID callerId) {
        Visit visit = requireVisit(visitId);
        if (callerId == null || !callerId.equals(visit.getVisitorId())) {
            throw new ForbiddenException(NotraiusErrors.VISIT_CANCEL_FORBIDDEN,
                    "Only the visitor may cancel visit " + visitId);
        }

        String status = statusCodeOf(visit);
        if (!CANCELLABLE.contains(status)) {
            throw new ConflictException(NotraiusErrors.VISIT_INVALID_STATE,
                    "Visit " + visitId + " is " + status
                            + "; a started or finished visit is decided by its clocks, not by a cancel");
        }

        boolean confirmed = NotraiusStatuses.VISIT_CONFIRMED.equals(status);
        if (confirmed) {
            cancellationRequireOutsideHorizon(visit);
        }

        // The refund comes first and returns whole, so pay's no-refund-after-settle
        // rule is never approached: the penalty is a separate, later payment.
        visitLedgerService.refundInFull(visit, "visit cancelled by the visitor");
        if (confirmed) {
            cancellationRaiseCancellationPenalty(visit);
            cancellationFileCancellationJudgment(visit);
        }

        VisitResponse response = visitLedgerService.moveTo(visit, NotraiusStatuses.VISIT_CANCELLED,
                NotraiusActivities.Visits.CANCEL, confirmed ? "CANCEL_CONFIRMED" : "CANCEL");
        log.info("Visit {} cancelled by the visitor ({}); {}",
                visitId, status, confirmed ? "full refund plus a 20% penalty" : "full refund, no penalty");
        return response;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // The paid door's two companions
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 20% of the fee, wallet to wallet, to the owner as of this act — new money
     * takes the owner it is born to, so a property that changed hands does not
     * redirect a penalty raised today. The reference is deterministic, so a
     * retried cancellation folds into the payment that already exists instead of
     * charging twice.
     */
    private void cancellationRaiseCancellationPenalty(Visit visit) {
        BigDecimal penalty = visit.getAmount()
                .multiply(CANCELLATION_PENALTY_RATE)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (penalty.signum() <= 0) {
            log.info("Visit {} carries no fee; no cancellation penalty to raise", visit.getId());
            return;
        }
        UUID reference = UUID.nameUUIDFromBytes(
                ("VISIT-CANCEL:" + visit.getId()).getBytes(StandardCharsets.UTF_8));
        String propertyTitle = visit.getPricing() != null && visit.getPricing().getProperty() != null
                ? visit.getPricing().getProperty().getTitle() : null;
        String reason = "Cancellation penalty: the visitor cancelled confirmed visit " + visit.getId()
                + (propertyTitle != null ? " at " + propertyTitle : "")
                + " inside the commitment; 20% of the fee compensates the owner for a slot withdrawn";
        payPaymentClient.createDirectPayment(visit.getVisitorId(), visit.getCurrencyId(), penalty,
                NotraiusActivities.Visits.PENALTY, reference, visit.getOwnerId(), reason);
        visitLedgerService.audit(NotraiusActivities.Visits.PENALTY, visit.getId(),
                "PENALTY " + penalty.toPlainString(),
                java.util.Map.of("amount", penalty.toPlainString(),
                        "referenceId", reference.toString(),
                        "beneficiaryId", visit.getOwnerId().toString()), null);
        log.info("Visit {} cancellation penalty {} raised from the visitor to the owner (ref {})",
                visit.getId(), penalty, reference);
    }

    /**
     * The judgment is filed on behalf of the party who did not cancel — the
     * assigned user, or the owner when no counterpart was ever stamped. It is
     * skipped only when the two would be the same person, which cannot happen for
     * a confirmed visit.
     */
    private void cancellationFileCancellationJudgment(Visit visit) {
        UUID author = visit.getUserId() != null ? visit.getUserId() : visit.getOwnerId();
        UUID target = visit.getVisitorId();
        if (author == null || author.equals(target)) {
            log.warn("Visit {}: no surviving party to author the cancellation judgment", visit.getId());
            return;
        }
        UUID activityId = activityRepository.findByCode(NotraiusActivities.Visits.CANCEL)
                .map(Activity::getId)
                .orElse(null);
        assessmentClient.fileAllAxesNegative(target, author, visit.getId(),
                NotraiusActivities.Visits.CANCEL, activityId,
                "Cancelled a confirmed visit after the commitment was made");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Gates
    // ═══════════════════════════════════════════════════════════════════════

    /** At-least semantics: exactly on the line is still cancellable. */
    private void cancellationRequireOutsideHorizon(Visit visit) {
        Instant start = visit.getValidity().getFromDate();
        Instant edge = start != null
                ? start.minus(properties.getVisitCancelHorizonHours(), ChronoUnit.HOURS)
                : null;
        if (edge == null || Instant.now().isAfter(edge)) {
            throw new ConflictException(NotraiusErrors.VISIT_CANCEL_WINDOW_CLOSED,
                    "Visit " + visit.getId() + " starts at " + start
                            + "; a confirmed visit can only be cancelled at least "
                            + properties.getVisitCancelHorizonHours()
                            + " hours before it — inside that line the door is the dispute ticket");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // VisitCompletionService
    // ═══════════════════════════════════════════════════════════════════

    /** The parties' own word that all is done: release, then COMPLETED. */
    @Transactional
    public VisitResponse confirmComplete(UUID visitId, UUID callerId) {
        Visit visit = visitRepository.findById(visitId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.VISIT_NOT_FOUND,
                        "Visit not found: " + visitId));
        if (callerId == null || !(callerId.equals(visit.getVisitorId())
                || callerId.equals(visit.getUserId()))) {
            throw new ForbiddenException(NotraiusErrors.VISIT_COMPLETION_FORBIDDEN,
                    "Only the visitor and the assigned user may complete visit " + visitId);
        }
        completionRequireStartedAndConfirmed(visit);
        if (!bothAssessed(visitId, visit)) {
            throw new ConflictException(NotraiusErrors.VISIT_ASSESSMENTS_INCOMPLETE,
                    "Visit " + visitId + " cannot be completed until both parties have assessed");
        }

        visitLedgerService.releaseToOwner(visit);
        VisitResponse response = visitLedgerService.complete(visit, "CONFIRM");
        log.info("Visit {} completed on both parties' word (by user={})", visitId, callerId);
        return response;
    }

    /**
     * The hour's word (5.3 sweep law C): a confirmed start whose assessment
     * window has run out completes and settles, gate skipped on purpose.
     */
    @Transactional
    public VisitResponse completeByHour(Visit visit) {
        visitLedgerService.releaseToOwner(visit);
        VisitResponse response = visitLedgerService.complete(visit, "ASSESSMENT_HOUR_ELAPSED");
        log.info("Visit {} completed by the assessment hour; split released without the judgments",
                visit.getId());
        return response;
    }

    private void completionRequireStartedAndConfirmed(Visit visit) {
        String status = visit.getStatus() != null ? visit.getStatus().getCode() : null;
        if (!NotraiusStatuses.VISIT_STARTED.equals(status) || visit.getStartedAt() == null) {
            throw new ConflictException(NotraiusErrors.VISIT_NOT_STARTED,
                    "Visit " + visit.getId() + " is " + status + "; only a started visit can complete");
        }
        if (visit.getStartConfirmedAt() == null) {
            throw new ConflictException(NotraiusErrors.VISIT_NOT_STARTED,
                    "Visit " + visit.getId() + "'s start was never countersigned, so it cannot be completed");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // VisitDisputeService
    // ═══════════════════════════════════════════════════════════════════

    private static final Set<String> TERMINAL = Set.of(
            NotraiusStatuses.VISIT_COMPLETED,
            NotraiusStatuses.VISIT_EXPIRED,
            NotraiusStatuses.VISIT_CANCELLED);

    /** Pay's words for custody that no longer exists. */
    private static final Set<String> DEAD_ESCROW = Set.of("REFUNDED", "RELEASED");

    /** Decide a disputed visit's money: REFUND to the visitor, RELEASE to the owner. */
    @Transactional
    public VisitResponse resolve(UUID visitId, VisitResolutionRequest request) {
        Visit visit = visitRepository.findById(visitId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.VISIT_NOT_FOUND,
                        "Visit not found: " + visitId));

        String decision = request.getDecision() != null
                ? request.getDecision().trim().toUpperCase(Locale.ROOT) : null;
        if (!"REFUND".equals(decision) && !"RELEASE".equals(decision)) {
            throw new BadRequestException(NotraiusErrors.VISIT_RESOLUTION_INVALID,
                    "A staff decision must be REFUND or RELEASE, not " + request.getDecision());
        }

        String status = visit.getStatus() != null ? visit.getStatus().getCode() : null;
        if (TERMINAL.contains(status)) {
            throw new ConflictException(NotraiusErrors.VISIT_INVALID_STATE,
                    "Visit " + visitId + " is " + status
                            + "; its money already reached a terminal state and cannot be reversed");
        }

        String operation = "RESOLVE " + decision
                + (request.getNote() != null && !request.getNote().isBlank()
                        ? ": " + request.getNote().trim() : "");

        if ("REFUND".equals(decision)) {
            disputeRecoverFundingIfUnknown(visit);
            if (visit.getPaymentId() != null) {
                visitLedgerService.refundInFull(visit, "staff decision on a disputed visit");
            }
            VisitResponse response = visitLedgerService.moveTo(visit, NotraiusStatuses.VISIT_CANCELLED,
                    NotraiusActivities.Visits.RESOLVE, disputeTruncate(operation));
            log.info("Visit {} resolved by staff: refunded in full", visitId);
            return response;
        }

        visitLedgerService.releaseToOwner(visit);
        visit.setCompletedAt(Instant.now());
        VisitResponse response = visitLedgerService.moveTo(visit, NotraiusStatuses.VISIT_COMPLETED,
                NotraiusActivities.Visits.RESOLVE, disputeTruncate(operation));
        log.info("Visit {} resolved by staff: split released to its born destination", visitId);
        return response;
    }

    /**
     * A refund needs a payment id. When the row never recorded one, pay's truth is
     * re-read before deciding there is nothing to return — an orphan funding must
     * still be found and handed back.
     */
    private void disputeRecoverFundingIfUnknown(Visit visit) {
        if (visit.getPaymentId() != null) {
            return;
        }
        Optional<PayPaymentClient.PaymentRef> truth = payPaymentClient.findByReference(visit.getId());
        if (truth.isEmpty()) {
            return;
        }
        PayPaymentClient.PaymentRef payment = truth.get();
        boolean live = payment.escrowStatus() == null || !DEAD_ESCROW.contains(payment.escrowStatus());
        if (live) {
            visit.setPaymentId(payment.id());
            payment.singleSplit().ifPresent(split -> visit.setPaymentSplitId(split.splitId()));
        }
    }

    /** The audit's operation column is a sentence, not a document. */
    private String disputeTruncate(String operation) {
        return operation.length() > 100 ? operation.substring(0, 100) : operation;
    }

    // ═══════════════════════════════════════════════════════════════════
    // VisitExpiryService
    // ═══════════════════════════════════════════════════════════════════

    /** The statuses the 24-hour fence (5.1) governs: not yet planned. */
    private static final List<String> PRE_PLANNING = List.of(
            NotraiusStatuses.VISIT_UNPAID,
            NotraiusStatuses.VISIT_REQUESTED,
            NotraiusStatuses.VISIT_ASSIGNED);

    /** The statuses the 72-hour fence (5.2) governs: the commitment never landed. */
    private static final List<String> PRE_CONFIRMATION = List.of(
            NotraiusStatuses.VISIT_PLANNING);

    /**
     * One expiry sweep, two fences. Returns how many visits it settled (expired
     * or adopted), so a caller — a test, an ops button — can see that it did real
     * work.
     * <p>
     * <b>Paged, not listed.</b> A million stale visits is a million hydrated rows
     * held at once and one unbounded transaction; instead each fence reads a
     * keyset page of {@code (createdAt, id)} keys ({@code sweep-page-size} at a
     * time) and settles each visit in its own transaction — so a refund that
     * cannot be delivered rolls back alone, and a worklist larger than
     * {@code sweep-row-budget} resumes from the ledger cursor on the next tick.
     * <p>
     * Deliberately not {@code @Transactional}: the pager opens one transaction per
     * row, which a pass-wide transaction would collapse into one.
     */
    public int expirySweep() {
        Instant now = Instant.now();
        Instant lookback = now.minus(properties.getVisitSweepLookbackHours(), ChronoUnit.HOURS);

        int settled = 0;
        // 5.1's fence: a paid request must have reached PLANNING (stamp AND
        // acceptance) inside 24 hours, or the visitor is refunded in full.
        Instant requestFence = now.minus(properties.getVisitRequestExpiryHours(), ChronoUnit.HOURS);
        settled += sweeps.run(SweepJobs.VISIT_EXPIRY_REQUEST,
                (afterKey, afterId, limit) -> visitRepository.findStalePage(PRE_PLANNING,
                        requestFence, lookback, afterKey, afterId, PageRequest.of(0, limit)),
                visitId -> expirySettleOne(visitId, PRE_PLANNING, requestFence, lookback,
                        "request never reached planning") ? 1 : 0);
        // 5.2's last fence: any visit still unconfirmed 72 hours after creation
        // expires and its money returns — the birth clock is spent on CONFIRMED.
        Instant confirmationFence = now.minus(properties.getVisitConfirmationFenceHours(), ChronoUnit.HOURS);
        settled += sweeps.run(SweepJobs.VISIT_EXPIRY_CONFIRMATION,
                (afterKey, afterId, limit) -> visitRepository.findStalePage(PRE_CONFIRMATION,
                        confirmationFence, lookback, afterKey, afterId, PageRequest.of(0, limit)),
                visitId -> expirySettleOne(visitId, PRE_CONFIRMATION, confirmationFence, lookback,
                        "visit never reached CONFIRMED") ? 1 : 0);
        if (settled > 0) {
            log.info("Visit expiry sweep settled {} visits", settled);
        }
        return settled;
    }

    /**
     * One stale visit, in its own transaction. The row is re-read here and its
     * status re-checked against the page that named it: a request that reached
     * planning while the pass walked its page must not be expired for silence —
     * the later state is the truth, the worklist was a snapshot.
     *
     * @return true when this row was settled
     */
    @Transactional
    public boolean expirySettleOne(UUID visitId, List<String> statuses, Instant fence,
                                   Instant lookback, String reason) {
        Visit visit = visitRepository.findById(visitId).orElse(null);
        if (visit == null) {
            return false;
        }
        String actual = visit.getStatus() != null ? visit.getStatus().getCode() : null;
        if (!statuses.contains(actual)) {
            return false;
        }
        if (visit.getCreatedAt() == null || !visit.getCreatedAt().isBefore(fence)) {
            // The row moved out of the fence while the page was being settled.
            return false;
        }
        if (visit.getCreatedAt().isBefore(lookback)) {
            // Older than the sweep's own horizon: a human must look, not a job.
            log.warn("Visit {} is older than the expiry sweep's lookback and stays untouched", visit.getId());
            return false;
        }
        return expirySettle(visit, reason);
    }

    /** Returns true when the visit's state actually changed. */
    private boolean expirySettle(Visit visit, String reason) {
        if (visit.getPaymentId() != null) {
            // Money is known to be held: hand it back, then close the row.
            expiryRefund(visit);
            expiryExpire(visit, reason);
            return true;
        }
        // No payment id: pay's truth decides, never our guess.
        Optional<PayPaymentClient.PaymentRef> truth = payPaymentClient.findByReference(visit.getId());
        if (truth.isEmpty()) {
            expiryExpire(visit, "no funding exists for this reference");
            return true;
        }
        PayPaymentClient.PaymentRef payment = truth.get();
        boolean live = payment.escrowStatus() == null || !DEAD_ESCROW.contains(payment.escrowStatus());
        if (live) {
            visit.setPaymentId(payment.id());
            payment.singleSplit().ifPresent(split -> visit.setPaymentSplitId(split.splitId()));
            visit.setStatus(statusResolver.resolve(Entities.VISITS, NotraiusStatuses.VISIT_REQUESTED));
            visit.setVersion(visit.getVersion() + 1);
            visitRepository.save(visit);
            expiryAudit(NotraiusActivities.Visits.RECONCILE, visit, "ADOPT");
            log.info("Visit {} adopted a funding the request never heard back about (payment {})",
                    visit.getId(), payment.id());
            return true;
        }
        expiryExpire(visit, "the funding was already returned");
        return true;
    }

    private void expiryRefund(Visit visit) {
        try {
            payPaymentClient.refund(visit.getPaymentId());
        } catch (RuntimeException e) {
            // Do NOT expiryExpire a row whose money we could not return: the visitor's
            // money is not lost to a failed transport, the next expirySweep retries.
            throw new IllegalStateException("Refund failed for payment " + visit.getPaymentId(), e);
        }
    }

    private void expiryExpire(Visit visit, String reason) {
        visit.setStatus(statusResolver.resolve(Entities.VISITS, NotraiusStatuses.VISIT_EXPIRED));
        visit.setVersion(visit.getVersion() + 1);
        visitRepository.save(visit);
        expiryAudit(NotraiusActivities.Visits.EXPIRE, visit, "EXPIRE");
        log.info("Visit {} expired: {}", visit.getId(), reason);
    }

    private void expiryAudit(String activityCode, Visit visit, String operation) {
        auditTrail.record(Entities.VISITS, activityCode, visit.getId(), operation,
                null, visitMapper.toResponse(visit));
    }

    // ═══════════════════════════════════════════════════════════════════
    // VisitOutcomeSweepService
    // ═══════════════════════════════════════════════════════════════════

    /** The no-show penalty: 40% of the visit fee (PLAN 5.3). */
    private static final BigDecimal NO_SHOW_PENALTY_RATE = new BigDecimal("0.40");

    /**
     * One pass over the three clocks. Returns how many visits it moved.
     * <p>
     * Each law reads a keyset page of ids — claims by their claim instant, silent
     * visits and elapsed hours by their planned end — and settles one row per
     * transaction. The page is a snapshot; every handler re-reads its row and
     * re-checks the law that named it, so a countersignature that landed while
     * the pass walked its page is respected and never overwritten.
     * <p>
     * Deliberately not {@code @Transactional}: the pager opens one transaction per
     * row, which a pass-wide transaction would collapse into one.
     */
    public int outcomeSweep() {
        Instant now = Instant.now();

        // Law A: STARTED and never countersigned inside the window → no-show.
        Instant claimBefore = now.minus(properties.getVisitStartConfirmWindowMinutes(), ChronoUnit.MINUTES);
        int settled = sweeps.run(SweepJobs.VISIT_OUTCOME_CLAIMS,
                (afterKey, afterId, limit) -> visitRepository.findLapsedClaimPage(
                        NotraiusStatuses.VISIT_STARTED, claimBefore, afterKey, afterId,
                        PageRequest.of(0, limit)),
                this::outcomeSettleClaim);

        // Law B: CONFIRMED, past its planned end, never claimed → refund, expire.
        settled += sweeps.run(SweepJobs.VISIT_OUTCOME_SILENT,
                (afterKey, afterId, limit) -> visitRepository.findUnclaimedPastEndPage(
                        NotraiusStatuses.VISIT_CONFIRMED, now, afterKey, afterId,
                        PageRequest.of(0, limit)),
                visitId -> outcomeSettleSilent(visitId, now));

        // Law C: the assessment hour elapsed on a confirmed start → complete, release.
        Instant endBefore = now.minus(properties.getVisitAssessmentWindowMinutes(), ChronoUnit.MINUTES);
        settled += sweeps.run(SweepJobs.VISIT_OUTCOME_HOUR,
                (afterKey, afterId, limit) -> visitRepository.findAssessmentHourPassedPage(
                        NotraiusStatuses.VISIT_STARTED, endBefore, afterKey, afterId,
                        PageRequest.of(0, limit)),
                visitId -> outcomeSettleHour(visitId, endBefore));

        if (settled > 0) {
            log.info("Visit outcome sweep settled {} visits", settled);
        }
        return settled;
    }

    /**
     * Law A on one visit, in its own transaction: still STARTED, claim stamped,
     * countersignature still missing. A countersignature that landed after the
     * page was read cancels the settlement — the answer is the truth.
     */
    @Transactional
    public int outcomeSettleClaim(UUID visitId) {
        Instant claimBefore = Instant.now()
                .minus(properties.getVisitStartConfirmWindowMinutes(), ChronoUnit.MINUTES);
        Visit visit = visitRepository.findById(visitId).orElse(null);
        if (visit == null
                || !NotraiusStatuses.VISIT_STARTED.equals(statusCodeOf(visit))
                || visit.getStartedAt() == null
                || visit.getStartConfirmedAt() != null
                || !visit.getStartedAt().isBefore(claimBefore)) {
            return 0;
        }
        outcomeSweepSettleNoShow(visit);
        return 1;
    }

    /** Law B on one visit, in its own transaction: still CONFIRMED, never claimed, past its end. */
    @Transactional
    public int outcomeSettleSilent(UUID visitId, Instant now) {
        Visit visit = visitRepository.findById(visitId).orElse(null);
        if (visit == null
                || !NotraiusStatuses.VISIT_CONFIRMED.equals(statusCodeOf(visit))
                || visit.getStartedAt() != null
                || visit.getValidity() == null
                || visit.getValidity().getToDate() == null
                || !visit.getValidity().getToDate().isBefore(now)) {
            return 0;
        }
        visitLedgerService.refundInFull(visit, "visit ended without ever being claimed");
        visitLedgerService.expire(visit, "no start mark before the planned end");
        return 1;
    }

    /** Law C on one visit, in its own transaction: still STARTED, countersigned, hour spent. */
    @Transactional
    public int outcomeSettleHour(UUID visitId, Instant endBefore) {
        Visit visit = visitRepository.findById(visitId).orElse(null);
        if (visit == null
                || !NotraiusStatuses.VISIT_STARTED.equals(statusCodeOf(visit))
                || visit.getStartConfirmedAt() == null
                || visit.getValidity() == null
                || visit.getValidity().getToDate() == null
                || !visit.getValidity().getToDate().isBefore(endBefore)) {
            return 0;
        }
        completeByHour(visit);
        return 1;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Law A, in full
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * The claim's author was there; the countersignature's author was not. Who
     * filed the claim decides who is judged, because the only party who could
     * have confirmed and did not is the other one.
     */
    private void outcomeSweepSettleNoShow(Visit visit) {
        UUID claimer = visit.getStartedBy();
        boolean visitorWasPresent = Objects.equals(claimer, visit.getVisitorId());

        // Money first: the custody always returns in full, whatever the penalty.
        visitLedgerService.refundInFull(visit, "visit start was never countersigned");

        if (visitorWasPresent) {
            // The counterpart withdrew: the visitor is the wronged party and no
            // money moves between them — only standing does.
            for (UUID absent : outcomeSweepAbsentCounterpartIds(visit)) {
                outcomeSweepFileNoShowJudgment(absent, visit.getVisitorId(), visit,
                        "The other party claimed the visit started and never countersigned the claim");
            }
        } else {
            // The visitor withdrew: the owner keeps a separated penalty, then the
            // visitor's standing carries the judgment the wronged owner files.
            outcomeSweepRaiseNoShowPenalty(visit);
            outcomeSweepFileNoShowJudgment(visit.getVisitorId(), claimer, visit,
                    "The visitor claimed the visit started and never countersigned the claim");
        }

        visitLedgerService.expire(visit, "start claim lapsed without a countersignature");
        log.info("Visit {} settled as a no-show; claimer was {}", visit.getId(),
                visitorWasPresent ? "the visitor" : "the counterpart");
    }

    /** The counterpart's ids: the assignee, and the owner when no assignee exists. */
    private Set<UUID> outcomeSweepAbsentCounterpartIds(Visit visit) {
        Set<UUID> ids = new LinkedHashSet<>();
        if (visit.getUserId() != null) {
            ids.add(visit.getUserId());
        } else if (visit.getOwnerId() != null) {
            ids.add(visit.getOwnerId());
        }
        return ids;
    }

    private void outcomeSweepRaiseNoShowPenalty(Visit visit) {
        BigDecimal penalty = visit.getAmount()
                .multiply(NO_SHOW_PENALTY_RATE)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (penalty.signum() <= 0) {
            log.info("Visit {} carries no fee; no penalty to raise", visit.getId());
            return;
        }
        UUID reference = UUID.nameUUIDFromBytes(
                ("VISIT-NO-SHOW:" + visit.getId()).getBytes(StandardCharsets.UTF_8));
        payPaymentClient.createDirectPayment(visit.getVisitorId(), visit.getCurrencyId(), penalty,
                NotraiusActivities.Visits.PENALTY, reference, visit.getOwnerId(),
                "No-show penalty: the visitor claimed the visit started and never confirmed it");
        log.info("Visit {} no-show penalty {} raised against the visitor (ref {})",
                visit.getId(), penalty, reference);
        // Provenance for the charge itself, so the ledger and the audit agree.
        visitLedgerService.audit(NotraiusActivities.Visits.PENALTY, visit.getId(),
                "PENALTY " + penalty.toPlainString(),
                Map.of("amount", penalty.toPlainString(),
                        "currencyId", visit.getCurrencyId().toString(),
                        "payerId", visit.getVisitorId().toString(),
                        "beneficiaryId", visit.getOwnerId().toString(),
                        "referenceId", reference.toString()), null);
    }

    private void outcomeSweepFileNoShowJudgment(UUID targetUserId, UUID authorUserId, Visit visit, String reason) {
        if (targetUserId == null || authorUserId == null) {
            log.warn("Visit {} no-show judgment skipped: missing target or author", visit.getId());
            return;
        }
        if (targetUserId.equals(authorUserId)) {
            log.warn("Visit {} no-show judgment skipped: author and target are the same user",
                    visit.getId());
            return;
        }
        assessmentClient.fileAllAxesNegative(targetUserId, authorUserId, visit.getId(),
                NotraiusActivities.Visits.NO_SHOW, outcomeSweepActivityId(NotraiusActivities.Visits.NO_SHOW), reason);
    }

    private UUID outcomeSweepActivityId(String code) {
        return activityRepository.findByCode(code)
                .map(Activity::getId)
                .orElse(null);
    }

    // ═══════════════════════════════════════════════════════════════════
    // VisitPlanningService
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Propose or counter the visit's time. The turn decides whether the caller is
     * opening, countering, or refining their own proposal inside the window.
     */
    @Transactional
    public VisitResponse proposeTime(UUID visitId, VisitTimeProposalRequest request, UUID callerId) {
        Visit visit = planningRequirePlanningVisit(visitId, callerId);

        Instant from = request.getFromDate();
        Instant to = request.getToDate() != null
                ? request.getToDate()
                : from != null ? from.plus(durationOf(visit), ChronoUnit.MINUTES) : null;
        if (from == null || to == null || !to.isAfter(from)) {
            throw new BadRequestException(NotraiusErrors.VISIT_WINDOW_INVALID,
                    "Window [" + from + ", " + to + ") does not have a positive length");
        }

        Optional<VisitTurnReader.Turn> lastEdit = visitTurnReader.lastEdit(visitId);
        if (lastEdit.isPresent() && Objects.equals(lastEdit.get().editorId(), callerId)) {
            Instant windowCloses = lastEdit.get().at()
                    .plus(properties.getVisitReeditWindowMinutes(), ChronoUnit.MINUTES);
            if (Instant.now().isAfter(windowCloses)) {
                // Explained, never mysterious: the turn state is in the error.
                throw new ConflictException(NotraiusErrors.VISIT_TURN_CLOSED,
                        "Your re-edit window closed at " + windowCloses + "; the counterpart must confirm or counter");
            }
        }

        VisitResponse before = visitMapper.toResponse(visit);
        Validity validity = visit.getValidity();
        validity.setFromDate(from);
        validity.setToDate(to);
        visit.setVersion(visit.getVersion() + 1);
        visitRepository.save(visit);

        VisitResponse after = visitMapper.toResponse(visit);
        audit(NotraiusActivities.Visits.TIME_PROPOSE, visitId, "PROPOSE", after, before);
        announceToParties(visit, "TIME_PROPOSED", callerId);
        notifyPartiesExcept(visit, callerId, "Visit time proposed",
                "Visit " + visitId + " was moved to [" + from + ", " + to
                        + "); confirm or counter it.");
        log.info("Visit {} time proposed for [{} , {}) by user={}", visitId, from, to, callerId);
        return after;
    }

    /**
     * Confirm the counterpart's proposal and enter CONFIRMED. The proposer can
     * never confirm: that is self-echo, not agreement.
     */
    @Transactional
    public VisitResponse confirmTime(UUID visitId, UUID callerId) {
        Visit visit = planningRequirePlanningVisit(visitId, callerId);

        Optional<VisitTurnReader.Turn> lastEdit = visitTurnReader.lastEdit(visitId);
        if (lastEdit.isEmpty()) {
            throw new ConflictException(NotraiusErrors.VISIT_TIME_PROPOSAL_MISSING,
                    "Visit " + visitId + " has no proposed time to confirm");
        }
        if (Objects.equals(lastEdit.get().editorId(), callerId)) {
            throw new ConflictException(NotraiusErrors.VISIT_SELF_CONFIRM,
                    "Visit " + visitId + ": the proposer cannot confirm their own proposal");
        }

        VisitResponse before = visitMapper.toResponse(visit);
        visit.setStatus(statusResolver.resolve(Entities.VISITS, NotraiusStatuses.VISIT_CONFIRMED));
        visit.setVersion(visit.getVersion() + 1);
        visitRepository.save(visit);

        VisitResponse after = visitMapper.toResponse(visit);
        audit(NotraiusActivities.Visits.TIME_CONFIRM, visitId, "CONFIRM", after, before);
        announceToParties(visit, "TIME_CONFIRMED", callerId);
        notifyPartiesExcept(visit, callerId, "Visit confirmed",
                "Visit " + visitId + " is confirmed for its agreed time.");
        log.info("Visit {} confirmed by user={}; the time is agreed", visitId, callerId);
        return after;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Gates
    // ═══════════════════════════════════════════════════════════════════════

    private Visit planningRequirePlanningVisit(UUID visitId, UUID callerId) {
        Visit visit = visitRepository.findById(visitId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.VISIT_NOT_FOUND,
                        "Visit not found: " + visitId));
        String status = visit.getStatus() != null ? visit.getStatus().getCode() : null;
        if (!NotraiusStatuses.VISIT_PLANNING.equals(status)) {
            throw new ConflictException(NotraiusErrors.VISIT_INVALID_STATE,
                    "Visit " + visitId + " is " + status + "; the schedule is edited in PLANNING");
        }
        planningRequireScheduler(visit, callerId);
        return visit;
    }

    /** The two who actually meet: the visitor and the assigned user, nobody else. */
    private void planningRequireScheduler(Visit visit, UUID callerId) {
        if (callerId == null
                || !(callerId.equals(visit.getVisitorId()) || callerId.equals(visit.getUserId()))) {
            throw new ForbiddenException(NotraiusErrors.VISIT_SCHEDULE_FORBIDDEN,
                    "Only the visitor and the assigned user may edit visit " + visit.getId());
        }
    }

    private Integer durationOf(Visit visit) {
        VisitPricing pricing = visit.getPricing();
        return pricing != null && pricing.getDurationMinutes() != null ? pricing.getDurationMinutes() : 30;
    }

    // ═══════════════════════════════════════════════════════════════════
    // VisitReferenceParties
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public String entityCode() {
        return Entities.VISITS;
    }

    @Override
    public boolean isParty(UUID referenceId, UUID userId) {
        if (referenceId == null || userId == null) {
            return false;
        }
        Optional<Visit> visit = visitRepository.findById(referenceId);
        return visit.map(v -> userId.equals(v.getVisitorId())
                        || userId.equals(v.getUserId())
                        || userId.equals(v.getOwnerId()))
                .orElse(false);
    }

    // ═══════════════════════════════════════════════════════════════════
    // VisitRequestService
    // ═══════════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════════════
    // Request = payment
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Request a visit and requestFund it in the same transaction.
     * <p>
     * The gates are checked before anything is written: the price must be live,
     * the property published (a listing that went dark between browsing and
     * requesting is refused), the property anchored in a currency, and the
     * visitor must not be the owner — visiting one's own property is not a
     * purchase.
     */
    @Transactional
    public VisitResponse requestVisit(VisitRequestCreateRequest request, UUID visitorId) {
        VisitPricing pricing = requestRequireLivePricing(request.getPricingId());
        Property property = pricing.getProperty();
        if (!NotraiusStatuses.PROPERTY_PUBLISHED.equals(requestStatusCodeOf(property))) {
            throw new ConflictException(NotraiusErrors.VISIT_PROPERTY_NOT_PUBLISHED,
                    "Property " + property.getId() + " is " + requestStatusCodeOf(property)
                            + " and cannot be visited");
        }
        if (visitorId.equals(property.getOwnerId())) {
            throw new BadRequestException(NotraiusErrors.VISIT_OWN_PROPERTY,
                    "Property " + property.getId() + " is already yours; it cannot be visited as a purchase");
        }
        if (property.getCurrencyId() == null) {
            throw new ConflictException(NotraiusErrors.VISIT_CURRENCY_UNRESOLVED,
                    "Property " + property.getId() + " has no currency, so it cannot be charged for");
        }

        Instant from = request.getFromDate();
        Instant to = request.getToDate() != null
                ? request.getToDate()
                : from.plus(pricing.getDurationMinutes(), ChronoUnit.MINUTES);
        requestRequireValidWindow(from, to);

        // The retry's law: an unpaid request of this visitor for this price is
        // REUSED, so the second click carries the same reference as the first.
        Visit visit = visitRepository.findReusable(visitorId, pricing.getId(), NotraiusStatuses.VISIT_UNPAID)
                .stream().findFirst()
                .orElse(null);
        boolean reused = visit != null;
        if (!reused) {
            Validity validity = statusResolver.openValidity(Entities.VISITS, NotraiusStatuses.VISIT_REQUESTED);
            validity.setFromDate(from);
            validity.setToDate(to);
            validityRepository.save(validity);

            visit = new Visit();
            visit.setVisitorId(visitorId);
            visit.setOwnerId(property.getOwnerId());
            visit.setPricing(pricing);
            visit.setValidity(validity);
            visit.setAmount(pricing.getAmount());
            visit.setCurrencyId(property.getCurrencyId());
            visit.setStatus(statusResolver.resolve(Entities.VISITS, NotraiusStatuses.VISIT_UNPAID));
            visit.setVersion(1);
            visit.setCreatedBy(visitorId);
            // Persisted BEFORE the funding call leaves: the reference (this id)
            // must exist whether or not the call ever answers.
            visitRepository.save(visit);
        }

        return requestFund(visit, pricing, property, visitorId, reused);
    }

    /**
     * The funding call and its two failure shapes. Everything before it is
     * already persisted; everything after it is bookkeeping.
     */
    private VisitResponse requestFund(Visit visit, VisitPricing pricing, Property property,
                               UUID visitorId, boolean reused) {
        try {
            PayPaymentClient.PaymentRef payment = payPaymentClient.createCustodialPayment(
                    visitorId,
                    property.getCurrencyId(),
                    visit.getAmount(),
                    NotraiusActivities.Visits.REQUEST,
                    visit.getId(),
                    property.getOwnerId());

            visit.setPaymentId(payment.id());
            payment.singleSplit().ifPresent(split -> visit.setPaymentSplitId(split.splitId()));
            visit.setStatus(statusResolver.resolve(Entities.VISITS, NotraiusStatuses.VISIT_REQUESTED));
            visit.setVersion(visit.getVersion() + 1);
            visitRepository.save(visit);

            VisitResponse response = visitMapper.toResponse(visit);
            audit(reused ? NotraiusActivities.Visits.REQUEST_ADOPT : NotraiusActivities.Visits.REQUEST,
                    visit.getId(), reused ? "ADOPT" : "REQUEST", response, null);
            announceToParties(visit, reused ? "REQUEST_ADOPTED" : "REQUESTED", visitorId);
            safeNotify(visit.getOwnerId(), "New visit request",
                    "A paid visit was requested for your property (visit " + visit.getId() + ").");
            log.info("Visit {} {} for visitor={} property={} amount={} payment={}",
                    visit.getId(), reused ? "adopted" : "requested", visitorId, property.getId(),
                    visit.getAmount(), payment.id());
            return response;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                // A refusal: nobody paid, so nothing stands. Throwing rolls the
                // freshly minted row back with the transaction.
                log.info("Visit request refused by pay for visitor={} property={}: {}",
                        visitorId, property.getId(), e.getStatusCode());
                throw new ConflictException(NotraiusErrors.VISIT_PAYMENT_REFUSED,
                        "The payment was refused (" + e.getStatusCode().value()
                                + "), so the visit was not requested");
            }
            return requestLeaveUnpaid(visit, property, "pay answered " + e.getStatusCode());
        } catch (RuntimeException e) {
            return requestLeaveUnpaid(visit, property, e.getMessage());
        }
    }

    /**
     * The unknown shape: pay rolled nothing and told us nothing, so the visit
     * stands unpaid and the sweep re-reads the payment's truth later. Never an
     * exception — the visitor must be able to see the row that may already hold
     * their money.
     */
    private VisitResponse requestLeaveUnpaid(Visit visit, Property property, String reason) {
        log.warn("Visit {} funding outcome unknown ({}); left UNPAID for reconciliation",
                visit.getId(), reason);
        VisitResponse response = visitMapper.toResponse(visit);
        audit(NotraiusActivities.Visits.REQUEST, visit.getId(), "UNPAID", response, null);
        return response;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Read
    // ═══════════════════════════════════════════════════════════════════════

    /** One visit, to a party to it — visitor, counterpart, or property owner. */
    @Transactional(readOnly = true)
    public VisitResponse getVisit(UUID visitId, UUID callerId) {
        Visit visit = requireVisit(visitId);
        requireOwnership("Only a party to the visit may read it", visit, callerId);
        return withActions(visit, callerId);
    }

    /**
     * The row plus this caller's next acts. One field, no extra read: the state
     * machine's answer is derived from the row and the clock (see
     * {@link VisitNextActions}), which is precisely why it belongs on the read
     * and not on every client that would otherwise re-derive it.
     */
    private VisitResponse withActions(Visit visit, UUID callerId) {
        VisitResponse response = visitMapper.toResponse(visit);
        response.setNextActions(VisitNextActions.of(visit, callerId, properties));
        return response;
    }

    /**
     * The visitor's own page — every status unless narrowed, newest first unless
     * ordered by the scheduled window.
     * <p>
     * The two filters exist because the list a person actually opens is not
     * "everything I ever booked": it is "the visits that still have something to
     * happen" (a status set), or "what is coming up" ({@code from=now}). Without
     * them a visitor with a year of history pages through it to find tomorrow's
     * visit, and every status question becomes a client-side filter over pages
     * the server already paid to read.
     */
    @Transactional(readOnly = true)
    public Page<VisitResponse> listMyVisits(UUID visitorId, List<String> statuses, Instant from, Instant to,
                                            Pageable pageable) {
        requireOrderedWindow(from, to);
        return visitRepository.searchAsVisitor(visitorId, checkedStatuses(statuses),
                        orEpoch(from), orForever(to), pageable)
                .map(visit -> withActions(visit, visitorId));
    }

    /**
     * The counterpart's page: the visits stamped to this caller <b>and</b> the
     * ones on properties they own — the owner's inbox.
     * <p>
     * The owner half is the reason this page exists. A paid request is born with
     * no assignee, and assigning is the owner's own act (PLAN 5.2), so a page that
     * read only the assignee column would leave an owner with no door at all onto
     * the requests waiting for them.
     */
    @Transactional(readOnly = true)
    public Page<VisitResponse> listAssignedVisits(UUID userId, List<String> statuses, Instant from, Instant to,
                                                  Pageable pageable) {
        requireOrderedWindow(from, to);
        return visitRepository.searchAsParty(userId, checkedStatuses(statuses),
                        orEpoch(from), orForever(to), pageable)
                .map(visit -> withActions(visit, userId));
    }

    /**
     * The page's order, by name, refused rather than guessed.
     * <p>
     * {@code recent} is the API's historical order (newest booking first) and
     * stays the default so nothing that already reads this page changes shape;
     * {@code window} is the visitor's order — soonest visit first, which is what
     * an "upcoming" tab means. An unknown name is a client fault with its own
     * code, not a silent fallback that leaves the caller ordering by a rule they
     * never chose.
     */
    public static Sort sortOf(String sort) {
        String name = sort == null || sort.isBlank() ? "recent" : sort.trim().toLowerCase(Locale.ROOT);
        return switch (name) {
            case "recent" -> Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"));
            case "window" -> Sort.by(Sort.Direction.ASC, "validity.fromDate").and(Sort.by("id"));
            default -> throw new BadRequestException(NotraiusErrors.VISIT_FILTER_INVALID,
                    "sort must be 'recent' or 'window', not '" + sort + "'");
        };
    }

    /** The open end of a window filter, far beyond any tenancy. */
    private static final Instant FOREVER = Instant.parse("9999-12-31T23:59:59Z");

    /** Every visit status, in the order the story walks them. */
    private static final List<String> ALL_VISIT_STATUSES = List.of(
            NotraiusStatuses.VISIT_UNPAID,
            NotraiusStatuses.VISIT_REQUESTED,
            NotraiusStatuses.VISIT_ASSIGNED,
            NotraiusStatuses.VISIT_PLANNING,
            NotraiusStatuses.VISIT_CONFIRMED,
            NotraiusStatuses.VISIT_STARTED,
            NotraiusStatuses.VISIT_COMPLETED,
            NotraiusStatuses.VISIT_EXPIRED,
            NotraiusStatuses.VISIT_CANCELLED);

    /**
     * The status filter as the query wants it: a non-empty set of real codes.
     * <p>
     * "No filter" becomes every status rather than a nullable parameter, so the
     * SQL keeps one {@code IN} the planner can index. A code that is not a visit
     * status is refused by name — silently answering an empty page for a typo
     * teaches the client the wrong lesson.
     */
    private static List<String> checkedStatuses(List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return ALL_VISIT_STATUSES;
        }
        Set<String> resolved = new LinkedHashSet<>();
        for (String status : statuses) {
            String code = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
            if (!ALL_VISIT_STATUSES.contains(code)) {
                throw new BadRequestException(NotraiusErrors.VISIT_FILTER_INVALID,
                        "'" + status + "' is not a visit status");
            }
            resolved.add(code);
        }
        return List.copyOf(resolved);
    }

    /** An inverted window is a client fault, not an empty page. */
    private static void requireOrderedWindow(Instant from, Instant to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BadRequestException(NotraiusErrors.VISIT_FILTER_INVALID,
                    "from must not be after to");
        }
    }

    /** "No lower bound" as an instant — the query has no nullable date bind. */
    private static Instant orEpoch(Instant from) {
        return from != null ? from : Instant.EPOCH;
    }

    /** "No upper bound" as an instant: no visit window outlives the year 9999. */
    private static Instant orForever(Instant to) {
        return to != null ? to : FOREVER;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // OwnershipGuard — who is a party to a visit?
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * args[0]: the visit, args[1]: the caller id. Three parties, exactly as the
     * plan names them: the visitor who paid, the operational counterpart
     * ({@code userId}), and the property owner who takes the money.
     */
    @Override
    public boolean canAccess(Object... args) {
        if (args.length < 2) {
            return false;
        }
        if (!(args[0] instanceof Visit visit) || !(args[1] instanceof UUID callerId)) {
            return false;
        }
        if (callerId == null) {
            return false;
        }
        return callerId.equals(visit.getVisitorId())
                || callerId.equals(visit.getUserId())
                || callerId.equals(visit.getOwnerId());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private VisitPricing requestRequireLivePricing(UUID pricingId) {
        VisitPricing pricing = visitPricingRepository.findById(pricingId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.VISIT_PRICING_NOT_FOUND,
                        "Visit pricing not found: " + pricingId));
        if (!NotraiusStatuses.VISIT_PRICING_ACTIVE.equals(requestStatusCodeOf(pricing))) {
            throw new ConflictException(NotraiusErrors.VISIT_PRICING_NOT_LIVE,
                    "Visit price " + pricingId + " is " + requestStatusCodeOf(pricing));
        }
        return pricing;
    }

    private void requestRequireValidWindow(Instant from, Instant to) {
        if (from == null || to == null || !to.isAfter(from)) {
            throw new BadRequestException(NotraiusErrors.VISIT_WINDOW_INVALID,
                    "Window [" + from + ", " + to + ") does not have a positive length");
        }
    }

    private String requestStatusCodeOf(VisitPricing pricing) {
        return pricing.getStatus() != null ? pricing.getStatus().getCode() : null;
    }

    private String requestStatusCodeOf(Property property) {
        return property.getStatus() != null ? property.getStatus().getCode() : null;
    }

    // ═══════════════════════════════════════════════════════════════════
    // VisitRescheduleService
    // ═══════════════════════════════════════════════════════════════════

    /** Offer a new time: CONFIRMED → PLANNING, awaiting the counterpart's confirmation. */
    @Transactional
    public VisitResponse reschedule(UUID visitId, VisitRescheduleRequest request, UUID callerId) {
        Visit visit = requireVisit(visitId);
        rescheduleRequireParty(visit, callerId);

        String status = statusCodeOf(visit);
        if (!NotraiusStatuses.VISIT_CONFIRMED.equals(status)) {
            throw new ConflictException(NotraiusErrors.VISIT_INVALID_STATE,
                    "Visit " + visitId + " is " + status + "; only a CONFIRMED visit can be rescheduled");
        }

        rescheduleRequireOutsideHorizon(visit);
        rescheduleRequireUnusedReschedule(visitId, callerId);

        Instant from = request.getFromDate();
        if (from == null) {
            throw new BadRequestException(NotraiusErrors.VISIT_WINDOW_INVALID,
                    "A reschedule needs the new start instant");
        }
        Instant to = request.getToDate() != null
                ? request.getToDate()
                : from.plus(durationOf(visit), ChronoUnit.MINUTES);
        if (to == null || !to.isAfter(from)) {
            throw new BadRequestException(NotraiusErrors.VISIT_WINDOW_INVALID,
                    "Window [" + from + ", " + to + ") does not have a positive length");
        }

        VisitResponse before = visitMapper.toResponse(visit);
        Validity validity = visit.getValidity();
        validity.setFromDate(from);
        validity.setToDate(to);
        visit.setStatus(statusResolver.resolve(Entities.VISITS, NotraiusStatuses.VISIT_PLANNING));
        visit.setVersion(visit.getVersion() + 1);
        visitRepository.save(visit);

        VisitResponse after = visitMapper.toResponse(visit);
        visitLedgerService.audit(NotraiusActivities.Visits.RESCHEDULE, visitId, "RESCHEDULE",
                after, before);
        announceToParties(visit, "RESCHEDULED", callerId);
        notifyPartiesExcept(visit, callerId, "Visit rescheduled",
                "Visit " + visitId + " was rescheduled to [" + from + ", " + to
                        + "); confirm the new time.");
        log.info("Visit {} rescheduled to [{}, {}) by user={}; no money moved, no judgment filed",
                visitId, from, to, callerId);
        return after;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Gates
    // ═══════════════════════════════════════════════════════════════════════

    /** Either party of the agreement — the visitor and the assigned user. */
    private void rescheduleRequireParty(Visit visit, UUID callerId) {
        if (callerId == null
                || !(callerId.equals(visit.getVisitorId()) || callerId.equals(visit.getUserId()))) {
            throw new ForbiddenException(NotraiusErrors.VISIT_SCHEDULE_FORBIDDEN,
                    "Only the visitor and the assigned user may reschedule visit " + visit.getId());
        }
    }

    /**
     * The door shuts the first second inside the line; the horizon itself is
     * still open (at-least semantics, measured against the current planned start).
     */
    private void rescheduleRequireOutsideHorizon(Visit visit) {
        Instant start = visit.getValidity().getFromDate();
        Instant edge = start != null
                ? start.minus(properties.getVisitCancelHorizonHours(), ChronoUnit.HOURS)
                : null;
        if (edge == null || Instant.now().isAfter(edge)) {
            throw new ConflictException(NotraiusErrors.VISIT_RESCHEDULE_WINDOW_CLOSED,
                    "Visit " + visit.getId() + " starts at " + start
                            + "; a reschedule must be requested at least "
                            + properties.getVisitCancelHorizonHours() + " hours before it");
        }
    }

    /**
     * Once per person per visit, counted from the audit history. A second attempt
     * is refused with its own error, not silently ignored, so the caller knows
     * their own door is spent and only the counterpart's remains.
     */
    private void rescheduleRequireUnusedReschedule(UUID visitId, UUID callerId) {
        UUID activityId = activityRepository.findByCode(NotraiusActivities.Visits.RESCHEDULE)
                .map(Activity::getId)
                .orElseThrow(() -> new IllegalStateException(
                        "Activity not seeded: " + NotraiusActivities.Visits.RESCHEDULE));
        long used = logsRepository.countByReferenceActivityAndCreator(visitId, activityId, callerId);
        if (used > 0) {
            throw new ConflictException(NotraiusErrors.VISIT_RESCHEDULE_LIMIT,
                    "You have already rescheduled visit " + visitId
                            + " once; only your counterpart's request remains");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // VisitStartService
    // ═══════════════════════════════════════════════════════════════════

    /** The claim: CONFIRMED → STARTED, stamped with who and when. */
    @Transactional
    public VisitResponse start(UUID visitId, UUID callerId) {
        Visit visit = requireVisit(visitId);
        startRequireParty(visit, callerId);
        if (!NotraiusStatuses.VISIT_CONFIRMED.equals(statusCodeOf(visit))) {
            throw new ConflictException(NotraiusErrors.VISIT_INVALID_STATE,
                    "Visit " + visitId + " is " + statusCodeOf(visit)
                            + "; only a CONFIRMED visit can be marked started");
        }

        Instant now = Instant.now();
        Instant from = visit.getValidity().getFromDate();
        Instant to = visit.getValidity().getToDate();
        if (from != null && now.isBefore(from)) {
            throw new ConflictException(NotraiusErrors.VISIT_START_TOO_EARLY,
                    "Visit " + visitId + " is planned for " + from + "; nothing can be claimed before then");
        }
        if (to != null && now.isAfter(to)) {
            throw new ConflictException(NotraiusErrors.VISIT_START_TOO_LATE,
                    "Visit " + visitId + " was planned to end at " + to + " and nobody claimed it started");
        }

        VisitResponse before = visitMapper.toResponse(visit);
        visit.setStartedAt(now);
        visit.setStartedBy(callerId);
        visit.setStatus(statusResolver.resolve(Entities.VISITS, NotraiusStatuses.VISIT_STARTED));
        visit.setVersion(visit.getVersion() + 1);
        visitRepository.save(visit);

        VisitResponse after = visitMapper.toResponse(visit);
        visitLedgerService.audit(NotraiusActivities.Visits.START, visitId, "START", after, before);
        announceToParties(visit, "STARTED", callerId);
        notifyPartiesExcept(visit, callerId, "Visit started",
                "Visit " + visitId + " was claimed started; countersign within the window.");
        log.info("Visit {} marked STARTED by user={} at {}", visitId, callerId, now);
        return after;
    }

    /**
     * The countersignature: the other party confirms the claim inside the
     * window. STARTED stands; the status does not move — the evidence does.
     */
    @Transactional
    public VisitResponse confirmStart(UUID visitId, UUID callerId) {
        Visit visit = requireVisit(visitId);
        startRequireParty(visit, callerId);
        if (!NotraiusStatuses.VISIT_STARTED.equals(statusCodeOf(visit))) {
            throw new ConflictException(NotraiusErrors.VISIT_NOT_STARTED,
                    "Visit " + visitId + " is not STARTED; there is no claim to confirm");
        }
        if (visit.getStartConfirmedAt() != null) {
            // Already countersigned: answering again changes nothing, so the
            // caller gets the settled state instead of a second entry.
            return visitMapper.toResponse(visit);
        }
        if (Objects.equals(visit.getStartedBy(), callerId)) {
            throw new ConflictException(NotraiusErrors.VISIT_START_SELF_CONFIRM,
                    "Visit " + visitId + ": the claimant cannot countersign their own claim");
        }
        Instant claimAt = visit.getStartedAt();
        Instant deadline = claimAt != null
                ? claimAt.plus(properties.getVisitStartConfirmWindowMinutes(), ChronoUnit.MINUTES)
                : null;
        if (deadline == null || Instant.now().isAfter(deadline)) {
            throw new ConflictException(NotraiusErrors.VISIT_START_TOO_LATE,
                    "The claim on visit " + visitId + " lapsed at " + deadline
                            + "; the no-show rule now decides it");
        }

        VisitResponse before = visitMapper.toResponse(visit);
        visit.setStartConfirmedAt(Instant.now());
        visit.setStartConfirmedBy(callerId);
        visit.setVersion(visit.getVersion() + 1);
        visitRepository.save(visit);

        VisitResponse after = visitMapper.toResponse(visit);
        visitLedgerService.audit(NotraiusActivities.Visits.START_CONFIRM, visitId, "CONFIRM", after, before);
        announceToParties(visit, "START_CONFIRMED", callerId);
        notifyPartiesExcept(visit, callerId, "Visit start countersigned",
                "Visit " + visitId + "'s start is confirmed; the visit is under way.");
        log.info("Visit {} start countersigned by user={}", visitId, callerId);
        return after;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Gates
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * The two who actually meet. The owner joins only when no counterpart was
     * ever stamped — somebody has to be able to answer for the property.
     */
    private void startRequireParty(Visit visit, UUID callerId) {
        if (callerId == null) {
            throw new ForbiddenException(NotraiusErrors.VISIT_START_FORBIDDEN,
                    "Visit " + visit.getId() + " requires a signed-in party");
        }
        boolean isVisitor = callerId.equals(visit.getVisitorId());
        boolean isCounterpart = callerId.equals(visit.getUserId());
        boolean isOwnerWithoutCounterpart = visit.getUserId() == null && callerId.equals(visit.getOwnerId());
        if (!isVisitor && !isCounterpart && !isOwnerWithoutCounterpart) {
            throw new ForbiddenException(NotraiusErrors.VISIT_START_FORBIDDEN,
                    "Only the visitor and the assigned user may act on visit " + visit.getId() + "'s start");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // VisitWindDownService
    // ═══════════════════════════════════════════════════════════════════

    /** What is still owed something: a request waiting, a plan, or a commitment. */
    private static final List<String> WIND_DOWN_STATUSES = List.of(
            NotraiusStatuses.VISIT_REQUESTED,
            NotraiusStatuses.VISIT_ASSIGNED,
            NotraiusStatuses.VISIT_PLANNING,
            NotraiusStatuses.VISIT_CONFIRMED);

    /**
     * Refund and cancel every visit this property still owes. Returns how many
     * were settled, so the caller can report what the withdrawal cost.
     *
     * @param ownerVoluntary true when the owner chose this (unpublish, cannot
     *                       host); false when staff forced it (suspension) — the
     *                       difference is only whether somebody is judged
     */
    @Transactional
    public int windDown(UUID propertyId, boolean ownerVoluntary, String reason) {
        int settled = 0;
        for (Visit visit : visitRepository.findLiveForProperty(propertyId, WIND_DOWN_STATUSES)) {
            windDownRefundCustody(visit);
            if (ownerVoluntary && windDownIsConfirmed(visit)) {
                windDownJudgeOwnerSide(visit);
            }
            visitLedgerService.moveTo(visit, NotraiusStatuses.VISIT_CANCELLED,
                    NotraiusActivities.Visits.CANCEL,
                    ownerVoluntary ? "OWNER_WITHDREW" : "STAFF_SHUTDOWN");
            settled++;
            log.info("Visit {} wound down: {}", visit.getId(), reason);
        }
        if (settled > 0) {
            log.info("Property {} wound down {} visit(s): {}", propertyId, settled, reason);
        }
        return settled;
    }

    /**
     * The money comes back whole. When no payment id was ever recorded, pay's
     * truth is re-read before deciding there is nothing to return — a funding
     * that succeeded without a definitive answer must still be found and refunded.
     */
    private void windDownRefundCustody(Visit visit) {
        if (visit.getPaymentId() != null) {
            visitLedgerService.refundInFull(visit, "property withdrew");
            return;
        }
        Optional<PayPaymentClient.PaymentRef> truth = payPaymentClient.findByReference(visit.getId());
        if (truth.isPresent()) {
            PayPaymentClient.PaymentRef payment = truth.get();
            boolean live = payment.escrowStatus() == null
                    || !DEAD_ESCROW.contains(payment.escrowStatus());
            if (live) {
                visit.setPaymentId(payment.id());
                visitLedgerService.refundInFull(visit, "property withdrew (funding recovered)");
            }
        }
    }

    /** The owner's counterpart is judged for a voluntary break of a commitment. */
    private void windDownJudgeOwnerSide(Visit visit) {
        UUID target = visit.getUserId() != null ? visit.getUserId() : visit.getOwnerId();
        UUID author = visit.getVisitorId();
        if (target == null || target.equals(author)) {
            return;
        }
        UUID activityId = activityRepository.findByCode(NotraiusActivities.Visits.CANCEL)
                .map(Activity::getId)
                .orElse(null);
        assessmentClient.fileAllAxesNegative(target, author, visit.getId(),
                NotraiusActivities.Visits.CANCEL, activityId,
                "The property withdrew a confirmed visit after the commitment was made");
    }

    private boolean windDownIsConfirmed(Visit visit) {
        return visit.getStatus() != null
                && NotraiusStatuses.VISIT_CONFIRMED.equals(visit.getStatus().getCode());
    }

}
