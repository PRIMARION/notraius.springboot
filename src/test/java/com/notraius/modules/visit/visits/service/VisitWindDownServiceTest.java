package com.notraius.modules.visit.visits.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notraius.common.audit.AuditTrail;
import com.notraius.common.integration.PayPaymentClient;
import com.notraius.common.integration.ReputationAssessmentClient;
import com.notraius.common.services.StatusResolver;
import com.notraius.common.services.VisitLedgerService;
import com.notraius.database.entities.asset.Property;
import com.notraius.database.entities.reference.Status;
import com.notraius.database.entities.reference.Validity;
import com.notraius.database.entities.visit.Visit;
import com.notraius.database.entities.visit.VisitPricing;
import com.notraius.database.repositories.visit.VisitRepository;
import com.notraius.modules.core.common.constants.NotraiusStatuses;
import com.notraius.modules.visit.visits.mapper.VisitMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.primarion.module.access.activity.database.entities.Activity;
import org.primarion.module.access.activity.database.repositories.ActivityRepository;
import org.primarion.module.access.audit.AuditLogWriter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 5.4's owner-fault exit: the money always returns in full and the 20% never
 * fires; only a voluntary owner break is judged, never a staff shutdown.
 */
@ExtendWith(MockitoExtension.class)
class VisitWindDownServiceTest {

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
        VisitLedgerService ledger = new VisitLedgerService(visitRepository, payPaymentClient,
                statusResolver, new VisitMapper(), new AuditTrail(new ObjectMapper().findAndRegisterModules(), auditLogWriter));
        service = new VisitServiceFixture()
                .visitRepository(visitRepository)
                .visitLedgerService(ledger)
                .payPaymentClient(payPaymentClient)
                .assessmentClient(assessmentClient)
                .activityRepository(activityRepository)
                .build();
        lenient().when(statusResolver.resolve(any(), any()))
                .thenAnswer(invocation -> status(invocation.getArgument(1)));
        lenient().when(activityRepository.findByCode("VISITS.CANCEL"))
                .thenReturn(Optional.of(activity()));
    }

    @Test
    @DisplayName("the owner withdraws: every live visit is refunded and cancelled, the confirmed one judged")
    void ownerWithdrawalRefundsAndJudges() {
        Visit confirmed = visit(NotraiusStatuses.VISIT_CONFIRMED);
        Visit planning = visit(NotraiusStatuses.VISIT_PLANNING);
        when(visitRepository.findLiveForProperty(eq(confirmed.getPricing().getProperty().getId()), any()))
                .thenReturn(List.of(confirmed, planning));

        int settled = service.windDown(confirmed.getPricing().getProperty().getId(), true,
                "owner unpublished the property");

        assertThat(settled).isEqualTo(2);
        verify(payPaymentClient).refund(confirmed.getPaymentId());
        verify(payPaymentClient).refund(planning.getPaymentId());
        verify(payPaymentClient, never()).createDirectPayment(any(), any(), any(), any(), any(), any(),
                any());
        verify(assessmentClient).fileAllAxesNegative(eq(ASSIGNEE), eq(VISITOR),
                eq(confirmed.getId()), eq("VISITS.CANCEL"), eq(ACTIVITY_ID), anyString());
        verify(assessmentClient, never()).fileAllAxesNegative(eq(ASSIGNEE), eq(VISITOR),
                eq(planning.getId()), any(), any(), any());
        assertThat(confirmed.getStatus().getCode()).isEqualTo(NotraiusStatuses.VISIT_CANCELLED);
        assertThat(planning.getStatus().getCode()).isEqualTo(NotraiusStatuses.VISIT_CANCELLED);
    }

    @Test
    @DisplayName("a staff shutdown refunds in full and judges nobody")
    void staffShutdownJudgesNobody() {
        Visit confirmed = visit(NotraiusStatuses.VISIT_CONFIRMED);
        when(visitRepository.findLiveForProperty(eq(confirmed.getPricing().getProperty().getId()), any()))
                .thenReturn(List.of(confirmed));

        service.windDown(confirmed.getPricing().getProperty().getId(), false,
                "property suspended by moderation");

        verify(payPaymentClient).refund(confirmed.getPaymentId());
        verify(assessmentClient, never()).fileAllAxesNegative(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a funding the visit never recorded is found through pay and still refunded")
    void unrecordedFundingIsRecovered() {
        Visit visit = visit(NotraiusStatuses.VISIT_REQUESTED);
        visit.setPaymentId(null);
        when(visitRepository.findLiveForProperty(eq(visit.getPricing().getProperty().getId()), any()))
                .thenReturn(List.of(visit));
        UUID paymentId = UUID.randomUUID();
        when(payPaymentClient.findByReference(visit.getId()))
                .thenReturn(Optional.of(new PayPaymentClient.PaymentRef(paymentId,
                        new BigDecimal("25.00"), "USD", "CAPTURED", "HELD", List.of())));

        service.windDown(visit.getPricing().getProperty().getId(), false, "suspended");

        verify(payPaymentClient).refund(paymentId);
        assertThat(visit.getPaymentId()).isEqualTo(paymentId);
    }

    @Test
    @DisplayName("a dead funding is not refunded twice")
    void deadFundingIsLeftAlone() {
        Visit visit = visit(NotraiusStatuses.VISIT_REQUESTED);
        visit.setPaymentId(null);
        when(visitRepository.findLiveForProperty(eq(visit.getPricing().getProperty().getId()), any()))
                .thenReturn(List.of(visit));
        when(payPaymentClient.findByReference(visit.getId()))
                .thenReturn(Optional.of(new PayPaymentClient.PaymentRef(UUID.randomUUID(),
                        new BigDecimal("25.00"), "USD", "REFUNDED", "REFUNDED", List.of())));

        service.windDown(visit.getPricing().getProperty().getId(), true, "unpublished");

        verify(payPaymentClient, never()).refund(any());
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
}
