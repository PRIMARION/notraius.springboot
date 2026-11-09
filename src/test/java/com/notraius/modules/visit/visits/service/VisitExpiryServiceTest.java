package com.notraius.modules.visit.visits.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notraius.common.integration.PayPaymentClient;
import com.notraius.common.services.StatusResolver;
import com.notraius.config.properties.NotraiusProperties;
import com.notraius.database.entities.asset.Property;
import com.notraius.database.entities.reference.Entities;
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
import org.primarion.module.access.audit.AuditLogWriter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stress check for 5.1's clock (PLAN.md 5.1: silence never keeps money): a paid
 * request that never reached planning is refunded and expired, an unpaid row is
 * judged by pay's truth rather than by our guess — and a refund that failed
 * never swallows a visit's money.
 */
@ExtendWith(MockitoExtension.class)
class VisitExpiryServiceTest {

    private static final UUID VISITOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private VisitRepository visitRepository;
    @Mock private PayPaymentClient payPaymentClient;
    @Mock private StatusResolver statusResolver;
    @Mock private AuditLogWriter auditLogWriter;

    private NotraiusProperties properties;
    private VisitService service;

    @BeforeEach
    void setUp() {
        properties = new NotraiusProperties();
        properties.setVisitRequestExpiryHours(24);
        properties.setVisitSweepLookbackHours(720);
        service = new VisitServiceFixture()
                .visitRepository(visitRepository)
                .payPaymentClient(payPaymentClient)
                .statusResolver(statusResolver)
                .properties(properties)
                .auditLogWriter(auditLogWriter)
                .build();
        org.mockito.Mockito.lenient().when(statusResolver.resolve(any(), any()))
                .thenAnswer(invocation -> status(invocation.getArgument(1)));
    }

    @Test
    @DisplayName("a paid request that never reached planning is refunded in full, then expired")
    void stalePaidRequestIsRefundedAndExpired() {
        Visit visit = visit(NotraiusStatuses.VISIT_REQUESTED, UUID.randomUUID(), 30);
        stalePage(visit);

        assertThat(service.expirySweep()).isEqualTo(1);

        verify(payPaymentClient).refund(visit.getPaymentId());
        assertThat(visit.getStatus().getCode()).isEqualTo(NotraiusStatuses.VISIT_EXPIRED);
        assertThat(visit.getVersion()).isEqualTo(2);
        verify(auditLogWriter).write(eq(Entities.VISITS), eq("VISITS.EXPIRE"), eq(visit.getId()),
                eq("EXPIRE"), any(), any());
    }

    @Test
    @DisplayName("a failed refund never expires the row — the money is not lost to a transport")
    void failedRefundLeavesTheVisitAlone() {
        Visit visit = visit(NotraiusStatuses.VISIT_REQUESTED, UUID.randomUUID(), 30);
        stalePage(visit);
        org.mockito.Mockito.doThrow(new IllegalStateException("pay down"))
                .when(payPaymentClient).refund(any());

        assertThat(service.expirySweep()).isEqualTo(0);

        assertThat(visit.getStatus().getCode()).isEqualTo(NotraiusStatuses.VISIT_REQUESTED);
        verify(visitRepository, never()).save(any());
    }

    @Test
    @DisplayName("an unpaid row with no funding at all simply expires — nobody was charged")
    void unpaidWithoutFundingExpires() {
        Visit visit = visit(NotraiusStatuses.VISIT_UNPAID, null, 30);
        stalePage(visit);
        when(payPaymentClient.findByReference(visit.getId())).thenReturn(Optional.empty());

        assertThat(service.expirySweep()).isEqualTo(1);

        verify(payPaymentClient, never()).refund(any());
        assertThat(visit.getStatus().getCode()).isEqualTo(NotraiusStatuses.VISIT_EXPIRED);
    }

    @Test
    @DisplayName("an unpaid row whose funding pay did take is adopted, never expired under it")
    void unpaidWithLiveFundingIsAdopted() {
        Visit visit = visit(NotraiusStatuses.VISIT_UNPAID, null, 30);
        UUID paymentId = UUID.randomUUID();
        UUID splitId = UUID.randomUUID();
        stalePage(visit);
        when(payPaymentClient.findByReference(visit.getId())).thenReturn(Optional.of(
                new PayPaymentClient.PaymentRef(paymentId, new BigDecimal("25.00"), "XOF", "CAPTURED", "HELD",
                        List.of(new PayPaymentClient.SplitInfo(splitId, OWNER, new BigDecimal("25.00"), "Visit")))));

        assertThat(service.expirySweep()).isEqualTo(1);

        assertThat(visit.getStatus().getCode()).isEqualTo(NotraiusStatuses.VISIT_REQUESTED);
        assertThat(visit.getPaymentId()).isEqualTo(paymentId);
        assertThat(visit.getPaymentSplitId()).isEqualTo(splitId);
        verify(payPaymentClient, never()).refund(any());
        verify(auditLogWriter).write(eq(Entities.VISITS), eq("VISITS.RECONCILE"), eq(visit.getId()),
                eq("ADOPT"), any(), any());
    }

    @Test
    @DisplayName("an unpaid row whose funding was already returned expires without a second refund")
    void unpaidWithDeadFundingExpires() {
        Visit visit = visit(NotraiusStatuses.VISIT_UNPAID, null, 30);
        stalePage(visit);
        when(payPaymentClient.findByReference(visit.getId())).thenReturn(Optional.of(
                new PayPaymentClient.PaymentRef(UUID.randomUUID(), new BigDecimal("25.00"), "XOF",
                        "CAPTURED", "REFUNDED", List.of())));

        assertThat(service.expirySweep()).isEqualTo(1);

        assertThat(visit.getStatus().getCode()).isEqualTo(NotraiusStatuses.VISIT_EXPIRED);
        verify(payPaymentClient, never()).refund(any());
    }

    @Test
    @DisplayName("the 72-hour last fence expires a planning visit that never confirmed")
    void planningVisitDiesAtTheLastFence() {
        Visit visit = visit(NotraiusStatuses.VISIT_PLANNING, UUID.randomUUID(), 80);
        stalePage(visit);

        assertThat(service.expirySweep()).isEqualTo(1);

        verify(payPaymentClient).refund(visit.getPaymentId());
        assertThat(visit.getStatus().getCode()).isEqualTo(NotraiusStatuses.VISIT_EXPIRED);
    }

    @Test
    @DisplayName("a row older than the sweep's horizon is left for a human")
    void oldRowsAreLeftAlone() {
        Visit visit = visit(NotraiusStatuses.VISIT_REQUESTED, UUID.randomUUID(), 24 * 60);
        stalePage(visit);

        assertThat(service.expirySweep()).isEqualTo(0);

        verify(payPaymentClient, never()).refund(any());
        assertThat(visit.getStatus().getCode()).isEqualTo(NotraiusStatuses.VISIT_REQUESTED);
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    /**
     * The sweep reads a keyset page of ids and then re-reads each row in its own
     * transaction: stub both halves, so the test drives the real pager.
     */
    private void stalePage(Visit visit) {
        when(visitRepository.findStalePage(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(new SweepRow(visit.getId(), visit.getCreatedAt())));
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));
    }

    private Visit visit(String statusCode, UUID paymentId, int hoursOld) {
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
        validity.setFromDate(Instant.now().plus(2, ChronoUnit.DAYS));

        Visit visit = new Visit();
        visit.setId(UUID.randomUUID());
        visit.setVisitorId(VISITOR);
        visit.setOwnerId(OWNER);
        visit.setPricing(pricing);
        visit.setValidity(validity);
        visit.setAmount(new BigDecimal("25.00"));
        visit.setCurrencyId(property.getCurrencyId());
        visit.setPaymentId(paymentId);
        visit.setStatus(status(statusCode));
        visit.setVersion(1);
        visit.setCreatedAt(Instant.now().minus(hoursOld, ChronoUnit.HOURS));
        return visit;
    }

    private Status status(String code) {
        Status status = new Status();
        status.setId(UUID.randomUUID());
        status.setCode(code);
        return status;
    }
}
