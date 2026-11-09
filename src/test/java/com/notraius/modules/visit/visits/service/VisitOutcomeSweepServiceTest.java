package com.notraius.modules.visit.visits.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notraius.common.audit.AuditTrail;
import com.notraius.common.integration.PayPaymentClient;
import com.notraius.common.integration.ReputationAssessmentClient;
import com.notraius.common.services.StatusResolver;
import com.notraius.common.services.VisitLedgerService;
import com.notraius.config.properties.NotraiusProperties;
import com.notraius.database.entities.asset.Property;
import com.notraius.database.entities.reference.Status;
import com.notraius.database.entities.reference.Validity;
import com.notraius.database.entities.visit.Visit;
import com.notraius.database.entities.visit.VisitPricing;
import com.notraius.database.repositories.visit.VisitRepository;
import com.notraius.modules.core.common.constants.NotraiusStatuses;
import com.notraius.modules.core.common.schedule.SweepRow;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 5.3's three clocks: the lapsed claim (no-show), the silent visit (refund, no
 * judgment), and the hour (complete and release). Every law fails closed — a
 * refused refund leaves the row exactly as it was.
 */
@ExtendWith(MockitoExtension.class)
class VisitOutcomeSweepServiceTest {

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
        properties.setVisitStartConfirmWindowMinutes(10);
        properties.setVisitAssessmentWindowMinutes(60);
        VisitLedgerService ledger = new VisitLedgerService(visitRepository, payPaymentClient,
                statusResolver, new VisitMapper(), new AuditTrail(new ObjectMapper().findAndRegisterModules(), auditLogWriter));
        service = new VisitServiceFixture()
                .visitRepository(visitRepository)
                .visitLedgerService(ledger)
                .assessmentClient(assessmentClient)
                .payPaymentClient(payPaymentClient)
                .activityRepository(activityRepository)
                .properties(properties)
                .build();
        lenient().when(statusResolver.resolve(any(), any()))
                .thenAnswer(invocation -> status(invocation.getArgument(1)));
    }

    @Test
    @DisplayName("law A, visitor present: the absent counterpart is judged, no money is taken")
    void noShowWithVisitorPresent() {
        Visit visit = lapsedClaim(VISITOR);
        lapsedPage(visit);
        when(activityRepository.findByCode("VISITS.NO_SHOW")).thenReturn(Optional.of(activity()));

        int settled = service.outcomeSweep();

        assertThat(settled).isEqualTo(1);
        assertThat(visit.getStatus().getCode()).isEqualTo(NotraiusStatuses.VISIT_EXPIRED);
        verify(payPaymentClient).refund(visit.getPaymentId());
        verify(payPaymentClient, never()).createDirectPayment(any(), any(), any(), any(), any(), any(),
                any());
        verify(assessmentClient).fileAllAxesNegative(eq(ASSIGNEE), eq(VISITOR), eq(visit.getId()),
                eq("VISITS.NO_SHOW"), eq(ACTIVITY_ID), anyString());
    }

    @Test
    @DisplayName("law A, visitor absent: full refund, then a 40% direct penalty and a bad judgment")
    void noShowWithVisitorAbsent() {
        Visit visit = lapsedClaim(ASSIGNEE);
        lapsedPage(visit);
        when(activityRepository.findByCode("VISITS.NO_SHOW")).thenReturn(Optional.of(activity()));

        service.outcomeSweep();

        verify(payPaymentClient).refund(visit.getPaymentId());
        UUID expectedReference = UUID.nameUUIDFromBytes(
                ("VISIT-NO-SHOW:" + visit.getId()).getBytes(StandardCharsets.UTF_8));
        verify(payPaymentClient).createDirectPayment(eq(VISITOR), eq(visit.getCurrencyId()),
                eq(new BigDecimal("10.00")), eq("VISITS.PENALTY"), eq(expectedReference), eq(OWNER),
                anyString());
        verify(assessmentClient).fileAllAxesNegative(eq(VISITOR), eq(ASSIGNEE), eq(visit.getId()),
                eq("VISITS.NO_SHOW"), eq(ACTIVITY_ID), anyString());
        assertThat(visit.getStatus().getCode()).isEqualTo(NotraiusStatuses.VISIT_EXPIRED);
    }

    @Test
    @DisplayName("law B: a silent visit is refunded in full and judged by nobody")
    void silentVisitIsRefunded() {
        Visit visit = silentVisit();
        silentPage(visit);

        int settled = service.outcomeSweep();

        assertThat(settled).isEqualTo(1);
        verify(payPaymentClient).refund(visit.getPaymentId());
        verify(assessmentClient, never()).fileAllAxesNegative(any(), any(), any(), any(), any(), any());
        assertThat(visit.getStatus().getCode()).isEqualTo(NotraiusStatuses.VISIT_EXPIRED);
    }

    @Test
    @DisplayName("law C: the hour releases the born split and completes, gate skipped")
    void assessmentHourCompletesAndReleases() {
        Visit visit = startedVisit();
        hourPage(visit);

        int settled = service.outcomeSweep();

        assertThat(settled).isEqualTo(1);
        verify(payPaymentClient).releaseSplit(visit.getPaymentId(), visit.getPaymentSplitId());
        verify(assessmentClient, never()).listForVisit(any());
        assertThat(visit.getStatus().getCode()).isEqualTo(NotraiusStatuses.VISIT_COMPLETED);
        assertThat(visit.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("a refused refund leaves the visit untouched for the next round")
    void failedRefundDoesNotMoveTheVisit() {
        Visit visit = lapsedClaim(VISITOR);
        lapsedPage(visit);
        doThrow(new IllegalStateException("pay is unavailable"))
                .when(payPaymentClient).refund(visit.getPaymentId());

        int settled = service.outcomeSweep();

        assertThat(settled).isZero();
        assertThat(visit.getStatus().getCode()).isEqualTo(NotraiusStatuses.VISIT_STARTED);
        verify(visitRepository, never()).save(any());
        verify(assessmentClient, never()).fileAllAxesNegative(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("the sweep settles what it can when one row fails and another does not")
    void oneBadRowDoesNotStopTheSweep() {
        Visit broken = lapsedClaim(VISITOR);
        Visit healthy = lapsedClaim(VISITOR);
        lapsedPage(broken, healthy);
        doThrow(new IllegalStateException("pay is unavailable"))
                .when(payPaymentClient).refund(broken.getPaymentId());
        when(activityRepository.findByCode("VISITS.NO_SHOW")).thenReturn(Optional.of(activity()));

        int settled = service.outcomeSweep();

        assertThat(settled).isEqualTo(1);
        assertThat(broken.getStatus().getCode()).isEqualTo(NotraiusStatuses.VISIT_STARTED);
        assertThat(healthy.getStatus().getCode()).isEqualTo(NotraiusStatuses.VISIT_EXPIRED);
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    /**
     * The sweep reads a keyset page of ids and re-reads each row in its own
     * transaction: stub both halves, so the test drives the real pager.
     */
    private void lapsedPage(Visit... visits) {
        when(visitRepository.findLapsedClaimPage(eq(NotraiusStatuses.VISIT_STARTED), any(), any(), any(), any()))
                .thenReturn(rows(visits));
        stubFind(visits);
    }

    private void silentPage(Visit... visits) {
        when(visitRepository.findUnclaimedPastEndPage(eq(NotraiusStatuses.VISIT_CONFIRMED), any(), any(), any(), any()))
                .thenReturn(rows(visits));
        stubFind(visits);
    }

    private void hourPage(Visit... visits) {
        when(visitRepository.findAssessmentHourPassedPage(eq(NotraiusStatuses.VISIT_STARTED), any(), any(), any(), any()))
                .thenReturn(rows(visits));
        stubFind(visits);
    }

    private List<com.notraius.modules.core.common.schedule.SweepKey> rows(Visit... visits) {
        return java.util.Arrays.stream(visits)
                .map(v -> (com.notraius.modules.core.common.schedule.SweepKey)
                        new SweepRow(v.getId(), Instant.now().minus(1, ChronoUnit.HOURS)))
                .toList();
    }

    private void stubFind(Visit... visits) {
        for (Visit visit : visits) {
            when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));
        }
    }

    private Visit lapsedClaim(UUID claimer) {
        Visit visit = baseVisit(NotraiusStatuses.VISIT_STARTED);
        visit.setStartedBy(claimer);
        // Claimed well before the countersignature window closed.
        visit.setStartedAt(Instant.now().minus(30, ChronoUnit.MINUTES));
        visit.getValidity().setFromDate(Instant.now().minus(40, ChronoUnit.MINUTES));
        visit.getValidity().setToDate(Instant.now().minus(10, ChronoUnit.MINUTES));
        return visit;
    }

    private Visit silentVisit() {
        Visit visit = baseVisit(NotraiusStatuses.VISIT_CONFIRMED);
        visit.getValidity().setFromDate(Instant.now().minus(2, ChronoUnit.HOURS));
        visit.getValidity().setToDate(Instant.now().minus(1, ChronoUnit.HOURS));
        return visit;
    }

    private Visit startedVisit() {
        Visit visit = baseVisit(NotraiusStatuses.VISIT_STARTED);
        visit.setStartedBy(VISITOR);
        visit.setStartedAt(Instant.now().minus(3, ChronoUnit.HOURS));
        visit.setStartConfirmedAt(Instant.now().minus(179, ChronoUnit.MINUTES));
        visit.setStartConfirmedBy(ASSIGNEE);
        visit.getValidity().setFromDate(Instant.now().minus(3, ChronoUnit.HOURS));
        visit.getValidity().setToDate(Instant.now().minus(2, ChronoUnit.HOURS));
        return visit;
    }

    private Visit baseVisit(String statusCode) {
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

        Visit visit = new Visit();
        visit.setId(UUID.randomUUID());
        visit.setVisitorId(VISITOR);
        visit.setUserId(ASSIGNEE);
        visit.setOwnerId(OWNER);
        visit.setPricing(pricing);
        visit.setValidity(new Validity());
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
        activity.setCode("VISITS.NO_SHOW");
        return activity;
    }

    private Status status(String code) {
        Status status = new Status();
        status.setId(UUID.randomUUID());
        status.setCode(code);
        return status;
    }
}
