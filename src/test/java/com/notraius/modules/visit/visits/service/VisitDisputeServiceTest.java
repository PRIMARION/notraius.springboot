package com.notraius.modules.visit.visits.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notraius.common.audit.AuditTrail;
import com.notraius.common.NotraiusErrors;
import com.notraius.common.integration.PayPaymentClient;
import com.notraius.common.services.StatusResolver;
import com.notraius.common.services.VisitLedgerService;
import com.notraius.database.entities.asset.Property;
import com.notraius.database.entities.reference.Status;
import com.notraius.database.entities.reference.Validity;
import com.notraius.database.entities.visit.Visit;
import com.notraius.database.entities.visit.VisitPricing;
import com.notraius.database.repositories.visit.VisitRepository;
import com.notraius.modules.core.common.constants.NotraiusStatuses;
import com.notraius.modules.visit.visits.dto.request.VisitResolutionRequest;
import com.notraius.modules.visit.visits.mapper.VisitMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.primarion.module.access.audit.AuditLogWriter;
import org.primarion.module.errors.AppException;
import org.primarion.module.errors.ConflictException;
import org.primarion.module.errors.ErrorCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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

/**
 * 5.5's staff decision: two whole outcomes through notraius's own money door,
 * no assessment in either direction, and nothing reversible after the money is
 * terminal.
 */
@ExtendWith(MockitoExtension.class)
class VisitDisputeServiceTest {

    private static final UUID VISITOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ASSIGNEE = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock private VisitRepository visitRepository;
    @Mock private StatusResolver statusResolver;
    @Mock private PayPaymentClient payPaymentClient;
    @Mock private AuditLogWriter auditLogWriter;

    private VisitService service;

    @BeforeEach
    void setUp() {
        VisitLedgerService ledger = new VisitLedgerService(visitRepository, payPaymentClient,
                statusResolver, new VisitMapper(), new AuditTrail(new ObjectMapper().findAndRegisterModules(), auditLogWriter));
        service = new VisitServiceFixture()
                .visitRepository(visitRepository)
                .visitLedgerService(ledger)
                .payPaymentClient(payPaymentClient)
                .build();
        lenient().when(statusResolver.resolve(any(), any()))
                .thenAnswer(invocation -> status(invocation.getArgument(1)));
    }

    @Test
    @DisplayName("REFUND returns the whole custody to the visitor and closes the visit")
    void refundDecision() {
        Visit visit = visit(NotraiusStatuses.VISIT_CONFIRMED);
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        var response = service.resolve(visit.getId(),
                new VisitResolutionRequest("refund", "Owner never opened the gate"));

        assertThat(response.getStatusCode()).isEqualTo(NotraiusStatuses.VISIT_CANCELLED);
        verify(payPaymentClient).refund(visit.getPaymentId());
        verify(payPaymentClient, never()).releaseSplit(any(), any());
        verify(auditLogWriter).write(any(), eq("VISITS.RESOLVE"), eq(visit.getId()),
                any(), any(), any());
    }

    @Test
    @DisplayName("RELEASE conveys the born split to the owner and completes the visit")
    void releaseDecision() {
        Visit visit = visit(NotraiusStatuses.VISIT_STARTED);
        visit.setStartedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        var response = service.resolve(visit.getId(),
                new VisitResolutionRequest("RELEASE", null));

        assertThat(response.getStatusCode()).isEqualTo(NotraiusStatuses.VISIT_COMPLETED);
        assertThat(visit.getCompletedAt()).isNotNull();
        verify(payPaymentClient).releaseSplit(visit.getPaymentId(), visit.getPaymentSplitId());
        verify(payPaymentClient, never()).refund(any());
    }

    @Test
    @DisplayName("a third option is not a decision")
    void unknownDecisionIsRefused() {
        Visit visit = visit(NotraiusStatuses.VISIT_CONFIRMED);
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        assertThatThrownBy(() -> service.resolve(visit.getId(),
                new VisitResolutionRequest("HALF", null)))
                .isInstanceOf(AppException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_RESOLUTION_INVALID));
        verify(payPaymentClient, never()).refund(any());
    }

    @Test
    @DisplayName("money that already reached a terminal state is never reversed")
    void terminalVisitIsRefused() {
        Visit visit = visit(NotraiusStatuses.VISIT_COMPLETED);
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        assertThatThrownBy(() -> service.resolve(visit.getId(),
                new VisitResolutionRequest("REFUND", null)))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_INVALID_STATE));
    }

    @Test
    @DisplayName("an orphan funding is found through pay before the refund is declared impossible")
    void orphanFundingIsRecoveredForTheRefund() {
        Visit visit = visit(NotraiusStatuses.VISIT_REQUESTED);
        visit.setPaymentId(null);
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));
        UUID paymentId = UUID.randomUUID();
        when(payPaymentClient.findByReference(visit.getId()))
                .thenReturn(Optional.of(new PayPaymentClient.PaymentRef(paymentId,
                        new BigDecimal("25.00"), "USD", "CAPTURED", "HELD",
                        List.of(new PayPaymentClient.SplitInfo(UUID.randomUUID(), OWNER,
                                new BigDecimal("25.00"), "Visit")))));

        service.resolve(visit.getId(), new VisitResolutionRequest("REFUND", null));

        verify(payPaymentClient).refund(paymentId);
        assertThat(visit.getPaymentSplitId()).isNotNull();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private Visit visit(String statusCode) {
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
        validity.setFromDate(Instant.now().plus(3, ChronoUnit.DAYS));
        validity.setToDate(Instant.now().plus(3, ChronoUnit.DAYS).plus(30, ChronoUnit.MINUTES));

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
