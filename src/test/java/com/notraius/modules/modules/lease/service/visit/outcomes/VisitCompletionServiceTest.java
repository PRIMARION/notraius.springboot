package com.notraius.modules.modules.lease.service.visit.outcomes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notraius.common.audit.AuditTrail;
import com.notraius.common.NotraiusErrors;
import com.notraius.common.integration.pay.PayPaymentClient;
import com.notraius.common.integration.pay.ReputationAssessmentClient;
import com.notraius.common.services.logs.StatusResolver;
import com.notraius.common.services.visit.VisitLedgerService;
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
import org.primarion.module.app.access.consent.AuditLogWriter;
import org.primarion.module.communication.errors.exception.AppException;
import org.primarion.module.communication.errors.client.ConflictException;
import org.primarion.module.communication.errors.core.ErrorCode;
import org.primarion.module.communication.errors.client.ForbiddenException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.notraius.modules.lease.visit.visits.service.VisitService;
import com.notraius.modules.modules.lease.service.visit.workflow.VisitServiceFixture;

/**
 * 5.3's completion: both witnesses, both judgments, then the born split releases
 * — and the hour, which settles without the judgments so silence cannot freeze
 * the owner's money.
 */
@ExtendWith(MockitoExtension.class)
class VisitCompletionServiceTest {

    private static final UUID VISITOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ASSIGNEE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID STRANGER = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock private VisitRepository visitRepository;
    @Mock private StatusResolver statusResolver;
    @Mock private PayPaymentClient payPaymentClient;
    @Mock private AuditLogWriter auditLogWriter;
    @Mock private ReputationAssessmentClient assessmentClient;

    private VisitService service;

    @BeforeEach
    void setUp() {
        VisitLedgerService ledger = new VisitLedgerService(visitRepository, payPaymentClient,
                statusResolver, new VisitMapper(), new AuditTrail(new ObjectMapper().findAndRegisterModules(), auditLogWriter),
                null, null, new ObjectMapper().findAndRegisterModules());
        service = new VisitServiceFixture()
                .visitRepository(visitRepository)
                .visitLedgerService(ledger)
                .assessmentClient(assessmentClient)
                .build();
        lenient().when(statusResolver.resolve(any(), any()))
                .thenAnswer(invocation -> status(invocation.getArgument(1)));
    }

    @Test
    @DisplayName("both judgments present: the split releases and the visit completes")
    void completesWhenBothAssessed() {
        Visit visit = startedVisit();
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));
        when(assessmentClient.listForVisit(visit.getId()))
                .thenReturn(List.of(assessmentBy(VISITOR), assessmentBy(ASSIGNEE)));

        var response = service.confirmComplete(visit.getId(), VISITOR);

        assertThat(response.getStatusCode()).isEqualTo(NotraiusStatuses.VISIT_COMPLETED);
        assertThat(visit.getCompletedAt()).isNotNull();
        verify(payPaymentClient).releaseSplit(visit.getPaymentId(), visit.getPaymentSplitId());
        verify(auditLogWriter).write(any(), eq("VISITS.COMPLETE"), eq(visit.getId()), eq("CONFIRM"),
                any(), any());
    }

    @Test
    @DisplayName("one missing judgment: nothing releases — the visit stays started")
    void refusesWithoutBothJudgments() {
        Visit visit = startedVisit();
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));
        when(assessmentClient.listForVisit(visit.getId())).thenReturn(List.of(assessmentBy(VISITOR)));

        assertThatThrownBy(() -> service.confirmComplete(visit.getId(), VISITOR))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_ASSESSMENTS_INCOMPLETE));
        verify(payPaymentClient, never()).releaseSplit(any(), any());
        assertThat(visit.getStatus().getCode()).isEqualTo(NotraiusStatuses.VISIT_STARTED);
    }

    @Test
    @DisplayName("an uncountersigned start cannot complete, whatever the judgments say")
    void refusesWithoutCountersignature() {
        Visit visit = startedVisit();
        visit.setStartConfirmedAt(null);
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        assertThatThrownBy(() -> service.confirmComplete(visit.getId(), VISITOR))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_NOT_STARTED));
        verify(payPaymentClient, never()).releaseSplit(any(), any());
    }

    @Test
    @DisplayName("completion belongs to the two who met, not to the owner or a stranger")
    void completionBelongsToThePair() {
        Visit visit = startedVisit();
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        assertThatThrownBy(() -> service.confirmComplete(visit.getId(), STRANGER))
                .isInstanceOf(ForbiddenException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_COMPLETION_FORBIDDEN));
    }

    @Test
    @DisplayName("the hour completes and releases without asking the gate")
    void hourCompletesWithoutTheGate() {
        Visit visit = startedVisit();

        var response = service.completeByHour(visit);

        assertThat(response.getStatusCode()).isEqualTo(NotraiusStatuses.VISIT_COMPLETED);
        verify(payPaymentClient).releaseSplit(visit.getPaymentId(), visit.getPaymentSplitId());
        verify(assessmentClient, never()).listForVisit(any());
        verify(auditLogWriter).write(any(), eq("VISITS.COMPLETE"), eq(visit.getId()),
                eq("ASSESSMENT_HOUR_ELAPSED"), any(), any());
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private Visit startedVisit() {
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
        validity.setFromDate(Instant.now().minus(2, ChronoUnit.HOURS));
        validity.setToDate(Instant.now().minus(90, ChronoUnit.MINUTES));

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
        visit.setStatus(status(NotraiusStatuses.VISIT_STARTED));
        visit.setStartedAt(Instant.now().minus(2, ChronoUnit.HOURS));
        visit.setStartedBy(VISITOR);
        visit.setStartConfirmedAt(Instant.now().minus(119, ChronoUnit.MINUTES));
        visit.setStartConfirmedBy(ASSIGNEE);
        visit.setVersion(3);
        return visit;
    }

    private ReputationAssessmentClient.AssessmentRef assessmentBy(UUID userId) {
        return new ReputationAssessmentClient.AssessmentRef(UUID.randomUUID(), null, null, null,
                userId, BigDecimal.ONE, null, Map.of(), Instant.now());
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
