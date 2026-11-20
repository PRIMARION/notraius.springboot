package com.notraius.modules.modules.lease.service.visit.requests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notraius.common.audit.AuditTrail;
import com.notraius.common.NotraiusErrors;
import com.notraius.common.integration.pay.PayPaymentClient;
import com.notraius.common.integration.pay.ReputationAssessmentClient;
import com.notraius.common.services.logs.StatusResolver;
import com.notraius.common.services.visit.VisitLedgerService;
import com.notraius.config.notraius.properties.NotraiusProperties;
import com.notraius.database.entities.asset.asset.Property;
import com.notraius.database.entities.lease.reference.status.Status;
import com.notraius.database.entities.lease.reference.amenity.Validity;
import com.notraius.database.entities.lease.visit.Visit;
import com.notraius.database.entities.lease.visit.VisitPricing;
import com.notraius.database.repositories.lease.visit.VisitRepository;
import com.notraius.modules.core.core.common.constants.NotraiusStatuses;
import com.notraius.modules.lease.visit.visits.VisitMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.primarion.module.app.access.consent.database.Activity;
import org.primarion.module.app.access.consent.database.ActivityRepository;
import org.primarion.module.app.access.consent.AuditLogWriter;
import org.primarion.module.communication.errors.exception.AppException;
import org.primarion.module.communication.errors.client.ConflictException;
import org.primarion.module.communication.errors.core.ErrorCode;
import org.primarion.module.communication.errors.client.ForbiddenException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.notraius.modules.lease.visit.visits.service.VisitService;
import com.notraius.modules.modules.lease.service.visit.workflow.VisitServiceFixture;

/**
 * 5.4's paid door: the visitor alone, outside the horizon, full refund first and
 * then a separate direct 20% — with the all-axes-1 judgment travelling alongside
 * it, and a free door before the commitment exists.
 */
@ExtendWith(MockitoExtension.class)
class VisitCancellationServiceTest {

    private static final UUID VISITOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ASSIGNEE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ACTIVITY_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Mock private VisitRepository visitRepository;
    @Mock private StatusResolver statusResolver;
    @Mock private PayPaymentClient payPaymentClient;
    @Mock private AuditLogWriter auditLogWriter;
    @Mock private ReputationAssessmentClient assessmentClient;
    @Mock private ActivityRepository activityRepository;

    private VisitService service;

    @BeforeEach
    void setUp() {
        NotraiusProperties properties = new NotraiusProperties();
        properties.setVisitCancelHorizonHours(24);
        VisitLedgerService ledger = new VisitLedgerService(visitRepository, payPaymentClient,
                statusResolver, new VisitMapper(), new AuditTrail(new ObjectMapper().findAndRegisterModules(), auditLogWriter),
                null, null, new ObjectMapper().findAndRegisterModules());
        service = new VisitServiceFixture()
                .visitRepository(visitRepository)
                .visitLedgerService(ledger)
                .payPaymentClient(payPaymentClient)
                .assessmentClient(assessmentClient)
                .activityRepository(activityRepository)
                .properties(properties)
                .build();
        lenient().when(statusResolver.resolve(any(), any()))
                .thenAnswer(invocation -> status(invocation.getArgument(1)));
        lenient().when(activityRepository.findByCode("VISITS.CANCEL"))
                .thenReturn(Optional.of(activity()));
    }

    @Test
    @DisplayName("cancelling a confirmed visit: full refund, then a separate direct 20%, plus a judgment")
    void confirmedCancellationPaysThePenalty() {
        Visit visit = visit(NotraiusStatuses.VISIT_CONFIRMED,
                Instant.now().plus(3, ChronoUnit.DAYS));
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        var response = service.cancel(visit.getId(), VISITOR);

        assertThat(response.getStatusCode()).isEqualTo(NotraiusStatuses.VISIT_CANCELLED);
        // 20% of 25.00, whole currency precision, on its own direct payment.
        verify(payPaymentClient).createDirectPayment(eq(VISITOR), eq(visit.getCurrencyId()),
                eq(new BigDecimal("5.00")), eq("VISITS.PENALTY"),
                eq(UUID.nameUUIDFromBytes(
                        ("VISIT-CANCEL:" + visit.getId()).getBytes(StandardCharsets.UTF_8))),
                eq(OWNER), anyString());
        verify(assessmentClient).fileAllAxesNegative(eq(VISITOR), eq(ASSIGNEE), eq(visit.getId()),
                eq("VISITS.CANCEL"), eq(ACTIVITY_ID), anyString());
    }

    @Test
    @DisplayName("the refund is whole and happens before the penalty is raised")
    void refundComesFirst() {
        Visit visit = visit(NotraiusStatuses.VISIT_CONFIRMED,
                Instant.now().plus(3, ChronoUnit.DAYS));
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        service.cancel(visit.getId(), VISITOR);

        var order = org.mockito.Mockito.inOrder(payPaymentClient);
        order.verify(payPaymentClient).refund(visit.getPaymentId());
        order.verify(payPaymentClient).createDirectPayment(any(), any(), any(), any(), any(), any(),
                any());
    }

    @Test
    @DisplayName("inside the horizon the door is shut — refusal, not a cheaper cancel")
    void insideHorizonIsRefused() {
        Visit visit = visit(NotraiusStatuses.VISIT_CONFIRMED,
                Instant.now().plus(2, ChronoUnit.HOURS));
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        assertThatThrownBy(() -> service.cancel(visit.getId(), VISITOR))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_CANCEL_WINDOW_CLOSED));
        verify(payPaymentClient, never()).refund(any());
    }

    @Test
    @DisplayName("before CONFIRMED the door is free: full refund, no penalty, no judgment")
    void preConfirmCancellationIsFree() {
        Visit visit = visit(NotraiusStatuses.VISIT_PLANNING, Instant.now().plus(1, ChronoUnit.HOURS));
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        var response = service.cancel(visit.getId(), VISITOR);

        assertThat(response.getStatusCode()).isEqualTo(NotraiusStatuses.VISIT_CANCELLED);
        verify(payPaymentClient).refund(visit.getPaymentId());
        verify(payPaymentClient, never()).createDirectPayment(any(), any(), any(), any(), any(), any(),
                any());
        verify(assessmentClient, never()).fileAllAxesNegative(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a started visit belongs to the no-show and hour laws, not to the cancel door")
    void startedVisitCannotBeCancelled() {
        Visit visit = visit(NotraiusStatuses.VISIT_STARTED, Instant.now().plus(1, ChronoUnit.HOURS));
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        assertThatThrownBy(() -> service.cancel(visit.getId(), VISITOR))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_INVALID_STATE));
    }

    @Test
    @DisplayName("the owner's side has no self-serve cancel on a confirmed visit")
    void ownerSideCannotCancel() {
        Visit visit = visit(NotraiusStatuses.VISIT_CONFIRMED,
                Instant.now().plus(3, ChronoUnit.DAYS));
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        assertThatThrownBy(() -> service.cancel(visit.getId(), ASSIGNEE))
                .isInstanceOf(ForbiddenException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_CANCEL_FORBIDDEN));
    }

    @Test
    @DisplayName("when no counterpart was stamped, the owner authors the judgment")
    void ownerAuthorsTheJudgmentWithoutACounterpart() {
        Visit visit = visit(NotraiusStatuses.VISIT_CONFIRMED,
                Instant.now().plus(3, ChronoUnit.DAYS));
        visit.setUserId(null);
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        service.cancel(visit.getId(), VISITOR);

        verify(assessmentClient).fileAllAxesNegative(eq(VISITOR), eq(OWNER), eq(visit.getId()),
                eq("VISITS.CANCEL"), eq(ACTIVITY_ID), anyString());
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private Visit visit(String statusCode, Instant from) {
        Property property = new Property();
        property.setId(UUID.randomUUID());
        property.setOwnerId(OWNER);
        property.setTitle("Villa");
        property.setCurrencyId(UUID.randomUUID());
        property.setStatus(status(NotraiusStatuses.PROPERTY_PUBLISHED));

        VisitPricing pricing = new VisitPricing();
        pricing.setId(UUID.randomUUID());
        pricing.setProperty(property);
        pricing.setAmount(new BigDecimal("25.00"));
        pricing.setDurationMinutes(30);
        pricing.setVersion(1);
        pricing.setStatus(status(NotraiusStatuses.VISIT_PRICING_ACTIVE));

        Validity validity = new Validity();
        validity.setFromDate(from);
        validity.setToDate(from.plus(30, ChronoUnit.MINUTES));

        Visit visit = new Visit();
        visit.setId(UUID.randomUUID());
        visit.setVisitorId(VISITOR);
        visit.setUserId(ASSIGNEE);
        visit.setOwnerId(OWNER);
        visit.setPricing(pricing);
        visit.setValidity(validity);
        visit.setAmount(new BigDecimal("25.00"));
        visit.setCurrencyId(property.getCurrencyId());
        visit.setPaymentId(UUID.randomUUID());
        visit.setPaymentSplitId(UUID.randomUUID());
        visit.setStatus(status(statusCode));
        visit.setVersion(3);
        return visit;
    }

    private Activity activity() {
        Activity activity = new Activity();
        activity.setId(ACTIVITY_ID);
        activity.setCode("VISITS.CANCEL");
        return activity;
    }

    private Status status(String code) {
        Status status = new Status();
        status.setId(UUID.randomUUID());
        status.setCode(code);
        return status;
    }

    private static ErrorCode errorOf(Throwable thrown) {
        assertThat(thrown).isInstanceOf(AppException.class);
        return ((AppException) thrown).getError();
    }
}
