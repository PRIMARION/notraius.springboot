package com.notraius.modules.modules.lease.service.visit.requests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notraius.common.NotraiusErrors;
import com.notraius.common.integration.pay.PayPaymentClient;
import com.notraius.common.services.logs.StatusResolver;
import com.notraius.database.entities.asset.asset.Property;
import com.notraius.database.entities.lease.reference.amenity.Entities;
import com.notraius.database.entities.lease.reference.status.Status;
import com.notraius.database.entities.lease.reference.status.Type;
import com.notraius.database.entities.lease.reference.amenity.Validity;
import com.notraius.database.entities.lease.visit.Visit;
import com.notraius.database.entities.lease.visit.VisitPricing;
import com.notraius.database.repositories.lease.reference.communication.ValidityRepository;
import com.notraius.database.repositories.lease.visit.VisitPricingRepository;
import com.notraius.database.repositories.lease.visit.VisitRepository;
import com.notraius.modules.core.core.common.constants.NotraiusStatuses;
import com.notraius.modules.lease.visit.visits.dto.request.visit.VisitRequestCreateRequest;
import com.notraius.modules.lease.visit.visits.dto.response.VisitResponse;
import com.notraius.modules.lease.visit.visits.VisitMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.primarion.module.app.access.consent.AuditLogWriter;
import org.primarion.module.communication.errors.exception.AppException;
import org.primarion.module.communication.errors.client.ConflictException;
import org.primarion.module.communication.errors.core.ErrorCode;
import org.primarion.module.communication.errors.client.ForbiddenException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

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
import com.notraius.modules.lease.visit.visits.service.VisitService;
import com.notraius.modules.modules.lease.service.visit.workflow.VisitServiceFixture;

/**
 * Stress check for the request sub-module (PLAN.md 5.1): the request is the
 * payment, the row is minted before the funding call leaves, a refusal rolls
 * back while an unknown leaves the row truthfully unpaid, and the money follows
 * the property while the beneficiary is the property owner — never the visit's
 * own counterpart.
 */
@ExtendWith(MockitoExtension.class)
class VisitRequestServiceTest {

    private static final UUID VISITOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID STRANGER = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock private VisitPricingRepository visitPricingRepository;
    @Mock private VisitRepository visitRepository;
    @Mock private ValidityRepository validityRepository;
    @Mock private PayPaymentClient payPaymentClient;
    @Mock private StatusResolver statusResolver;
    @Mock private AuditLogWriter auditLogWriter;

    private VisitService service;

    @BeforeEach
    void setUp() {
        service = new VisitServiceFixture()
                .visitPricingRepository(visitPricingRepository)
                .visitRepository(visitRepository)
                .validityRepository(validityRepository)
                .payPaymentClient(payPaymentClient)
                .statusResolver(statusResolver)
                .auditLogWriter(auditLogWriter)
                .build();
        lenient().when(statusResolver.resolve(any(), any()))
                .thenAnswer(invocation -> status(invocation.getArgument(1)));
        lenient().when(statusResolver.openValidity(any(), any()))
                .thenAnswer(invocation -> new Validity());
    }

    // ── Gates ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an unpublished property cannot be visited — the listing went dark")
    void unpublishedPropertyIsRefused() {
        VisitPricing pricing = pricing(NotraiusStatuses.PROPERTY_DRAFT, NotraiusStatuses.VISIT_PRICING_ACTIVE);
        when(visitPricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));

        assertThatThrownBy(() -> service.requestVisit(request(pricing.getId()), VISITOR))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_PROPERTY_NOT_PUBLISHED));
        verify(visitRepository, never()).save(any());
    }

    @Test
    @DisplayName("a paused visit price cannot be booked")
    void pausedPricingIsRefused() {
        VisitPricing pricing = pricing(NotraiusStatuses.PROPERTY_PUBLISHED, NotraiusStatuses.VISIT_PRICING_PAUSED);
        when(visitPricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));

        assertThatThrownBy(() -> service.requestVisit(request(pricing.getId()), VISITOR))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_PRICING_NOT_LIVE));
    }

    @Test
    @DisplayName("an owner cannot buy a visit of their own property")
    void ownPropertyIsRefused() {
        VisitPricing pricing = pricing(NotraiusStatuses.PROPERTY_PUBLISHED, NotraiusStatuses.VISIT_PRICING_ACTIVE);
        when(visitPricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));

        assertThatThrownBy(() -> service.requestVisit(request(pricing.getId()), OWNER))
                .isInstanceOf(AppException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_OWN_PROPERTY));
    }

    @Test
    @DisplayName("a window that does not run forwards is refused")
    void invertedWindowIsRefused() {
        VisitPricing pricing = pricing(NotraiusStatuses.PROPERTY_PUBLISHED, NotraiusStatuses.VISIT_PRICING_ACTIVE);
        when(visitPricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));

        Instant from = Instant.parse("2026-06-01T10:00:00Z");
        VisitRequestCreateRequest request = new VisitRequestCreateRequest(pricing.getId(), from,
                from.minus(1, ChronoUnit.HOURS));

        assertThatThrownBy(() -> service.requestVisit(request, VISITOR))
                .isInstanceOf(AppException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_WINDOW_INVALID));
    }

    // ── The act ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("the request funds itself and holds the money before anyone is told")
    void requestIsThePayment() {
        VisitPricing pricing = pricing(NotraiusStatuses.PROPERTY_PUBLISHED, NotraiusStatuses.VISIT_PRICING_ACTIVE);
        when(visitPricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(visitRepository.findReusable(eq(VISITOR), eq(pricing.getId()), eq(NotraiusStatuses.VISIT_UNPAID)))
                .thenReturn(List.of());
        when(payPaymentClient.createCustodialPayment(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PayPaymentClient.PaymentRef(UUID.randomUUID(), new BigDecimal("25.00"), "XOF",
                        "CAPTURED", "HELD",
                        List.of(new PayPaymentClient.SplitInfo(UUID.randomUUID(), OWNER,
                                new BigDecimal("25.00"), "Visit at the property"))));

        VisitResponse response = service.requestVisit(request(pricing.getId()), VISITOR);

        assertThat(response.getStatusCode()).isEqualTo(NotraiusStatuses.VISIT_REQUESTED);
        assertThat(response.getVisitorId()).isEqualTo(VISITOR);
        assertThat(response.getOwnerId()).isEqualTo(OWNER);
        assertThat(response.getAssigneeId()).isNull();
        assertThat(response.getAmount()).isEqualByComparingTo("25.00");
        assertThat(response.getPaymentId()).isNotNull();
        assertThat(response.getVersion()).isEqualTo(2);

        // The order is the law: the row is persisted BEFORE the funding call
        // leaves, then updated with what pay answered.
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(visitRepository, payPaymentClient);
        inOrder.verify(visitRepository).save(any(Visit.class));
        inOrder.verify(payPaymentClient).createCustodialPayment(any(), any(), any(), any(), any(), any());
        inOrder.verify(visitRepository).save(any(Visit.class));
        verify(validityRepository).save(any(Validity.class));
        verify(auditLogWriter).write(eq(Entities.VISITS), eq("VISITS.REQUEST"), any(), eq("REQUEST"),
                any(), any());
    }

    @Test
    @DisplayName("the funding names the property owner, and the visit keeps the split")
    void splitBelongsToThePropertyOwner() {
        VisitPricing pricing = pricing(NotraiusStatuses.PROPERTY_PUBLISHED, NotraiusStatuses.VISIT_PRICING_ACTIVE);
        UUID splitId = UUID.randomUUID();
        when(visitPricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(visitRepository.findReusable(any(), any(), any())).thenReturn(List.of());
        when(payPaymentClient.createCustodialPayment(eq(VISITOR), any(), any(), any(), any(), eq(OWNER)))
                .thenReturn(new PayPaymentClient.PaymentRef(UUID.randomUUID(), new BigDecimal("25.00"), "XOF",
                        "CAPTURED", "HELD",
                        List.of(new PayPaymentClient.SplitInfo(splitId, OWNER, new BigDecimal("25.00"), "Visit"))));

        service.requestVisit(request(pricing.getId()), VISITOR);

        ArgumentCaptor<Visit> saved = ArgumentCaptor.forClass(Visit.class);
        verify(visitRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(1).getPaymentSplitId()).isEqualTo(splitId);
        assertThat(saved.getAllValues().get(0).getOwnerId()).isEqualTo(OWNER);
    }

    @Test
    @DisplayName("a refusal rolls the request back — a visit nobody paid for never stands")
    void refusalRollsBack() {
        VisitPricing pricing = pricing(NotraiusStatuses.PROPERTY_PUBLISHED, NotraiusStatuses.VISIT_PRICING_ACTIVE);
        when(visitPricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(visitRepository.findReusable(any(), any(), any())).thenReturn(List.of());
        when(payPaymentClient.createCustodialPayment(any(), any(), any(), any(), any(), any()))
                .thenThrow(HttpClientErrorException.create(HttpStatus.PAYMENT_REQUIRED, "insufficient funds",
                        HttpHeaders.EMPTY, null, null));

        assertThatThrownBy(() -> service.requestVisit(request(pricing.getId()), VISITOR))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_PAYMENT_REFUSED));
        // Only the mint happened; the funded save never did.
        verify(visitRepository, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    @DisplayName("an unknown outcome leaves the row UNPAID — pay may already hold the money")
    void unknownOutcomeLeavesRowUnpaid() {
        VisitPricing pricing = pricing(NotraiusStatuses.PROPERTY_PUBLISHED, NotraiusStatuses.VISIT_PRICING_ACTIVE);
        when(visitPricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(visitRepository.findReusable(any(), any(), any())).thenReturn(List.of());
        when(payPaymentClient.createCustodialPayment(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("read timed out"));

        VisitResponse response = service.requestVisit(request(pricing.getId()), VISITOR);

        assertThat(response.getStatusCode()).isEqualTo(NotraiusStatuses.VISIT_UNPAID);
        assertThat(response.getPaymentId()).isNull();
        assertThat(response.getVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("a retried request adopts its own unpaid row — same reference, one charge")
    void retryAdoptsTheUnpaidRow() {
        VisitPricing pricing = pricing(NotraiusStatuses.PROPERTY_PUBLISHED, NotraiusStatuses.VISIT_PRICING_ACTIVE);
        Visit existing = visit(pricing, NotraiusStatuses.VISIT_UNPAID, null);
        when(visitPricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(visitRepository.findReusable(eq(VISITOR), eq(pricing.getId()), eq(NotraiusStatuses.VISIT_UNPAID)))
                .thenReturn(List.of(existing));
        when(payPaymentClient.createCustodialPayment(eq(VISITOR), any(), any(), any(), eq(existing.getId()), eq(OWNER)))
                .thenReturn(new PayPaymentClient.PaymentRef(UUID.randomUUID(), new BigDecimal("25.00"), "XOF",
                        "CAPTURED", "HELD", List.of()));

        VisitResponse response = service.requestVisit(request(pricing.getId()), VISITOR);

        assertThat(response.getId()).isEqualTo(existing.getId());
        assertThat(response.getStatusCode()).isEqualTo(NotraiusStatuses.VISIT_REQUESTED);
        // No second row was minted and no validity was minted for a reuse.
        verify(validityRepository, never()).save(any());
        verify(visitRepository, org.mockito.Mockito.times(1)).save(any());
    }

    // ── Read gates ────────────────────────────────────────────────────────

    @Test
    @DisplayName("a visit is readable by its three parties and nobody else")
    void visitIsReadableByItsParties() {
        VisitPricing pricing = pricing(NotraiusStatuses.PROPERTY_PUBLISHED, NotraiusStatuses.VISIT_PRICING_ACTIVE);
        Visit visit = visit(pricing, NotraiusStatuses.VISIT_REQUESTED, UUID.randomUUID());
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        assertThat(service.getVisit(visit.getId(), VISITOR).getId()).isEqualTo(visit.getId());
        assertThat(service.getVisit(visit.getId(), OWNER).getId()).isEqualTo(visit.getId());
        assertThatThrownBy(() -> service.getVisit(visit.getId(), STRANGER))
                .isInstanceOf(ForbiddenException.class);
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private VisitRequestCreateRequest request(UUID pricingId) {
        return new VisitRequestCreateRequest(pricingId, Instant.now().plus(2, ChronoUnit.DAYS), null);
    }

    private Visit visit(VisitPricing pricing, String statusCode, UUID paymentId) {
        Visit visit = new Visit();
        visit.setId(UUID.randomUUID());
        visit.setVisitorId(VISITOR);
        visit.setOwnerId(OWNER);
        visit.setPricing(pricing);
        Validity validity = new Validity();
        validity.setFromDate(Instant.now().plus(2, ChronoUnit.DAYS));
        validity.setToDate(Instant.now().plus(2, ChronoUnit.DAYS).plus(30, ChronoUnit.MINUTES));
        visit.setValidity(validity);
        visit.setAmount(new BigDecimal("25.00"));
        visit.setCurrencyId(pricing.getProperty().getCurrencyId());
        visit.setPaymentId(paymentId);
        visit.setStatus(status(statusCode));
        visit.setVersion(1);
        return visit;
    }

    private VisitPricing pricing(String propertyStatus, String pricingStatus) {
        Property property = new Property();
        property.setId(UUID.randomUUID());
        property.setOwnerId(OWNER);
        property.setTitle("Villa");
        property.setCurrencyId(UUID.randomUUID());
        property.setStatus(status(propertyStatus));

        VisitPricing pricing = new VisitPricing();
        pricing.setId(UUID.randomUUID());
        pricing.setProperty(property);
        Type type = new Type();
        type.setId(UUID.randomUUID());
        type.setCode("PHYSICAL");
        type.setStatus(status("ACTIVE"));
        pricing.setType(type);
        pricing.setAmount(new BigDecimal("25.00"));
        pricing.setDurationMinutes(30);
        pricing.setVersion(1);
        pricing.setStatus(status(pricingStatus));
        return pricing;
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
