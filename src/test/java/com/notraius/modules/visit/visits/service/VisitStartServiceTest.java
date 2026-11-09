package com.notraius.modules.visit.visits.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notraius.common.audit.AuditTrail;
import com.notraius.common.NotraiusErrors;
import com.notraius.common.integration.PayPaymentClient;
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
import org.primarion.module.errors.ForbiddenException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * 5.3's start pair: the claim is a promise, the countersignature is the witness,
 * and neither may be faked by the party who benefits from it.
 */
@ExtendWith(MockitoExtension.class)
class VisitStartServiceTest {

    private static final UUID VISITOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ASSIGNEE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID STRANGER = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock private VisitRepository visitRepository;
    @Mock private StatusResolver statusResolver;
    @Mock private PayPaymentClient payPaymentClient;
    @Mock private AuditLogWriter auditLogWriter;

    private VisitService service;
    private NotraiusProperties properties;

    @BeforeEach
    void setUp() {
        properties = new NotraiusProperties();
        properties.setVisitStartConfirmWindowMinutes(10);
        VisitLedgerService ledger = new VisitLedgerService(visitRepository, payPaymentClient,
                statusResolver, new VisitMapper(), new AuditTrail(new ObjectMapper().findAndRegisterModules(), auditLogWriter));
        service = new VisitServiceFixture()
                .visitRepository(visitRepository)
                .statusResolver(statusResolver)
                .visitLedgerService(ledger)
                .properties(properties)
                .build();
        lenient().when(statusResolver.resolve(any(), any()))
                .thenAnswer(invocation -> status(invocation.getArgument(1)));
    }

    @Test
    @DisplayName("a claim inside the planned window moves CONFIRMED to STARTED and stamps both facts")
    void claimInsideWindow() {
        Visit visit = confirmedVisit(Instant.now().minus(5, ChronoUnit.MINUTES),
                Instant.now().plus(25, ChronoUnit.MINUTES));
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        var response = service.start(visit.getId(), VISITOR);

        assertThat(response.getStatusCode()).isEqualTo(NotraiusStatuses.VISIT_STARTED);
        assertThat(visit.getStartedBy()).isEqualTo(VISITOR);
        assertThat(visit.getStartedAt()).isNotNull();
        assertThat(response.getStartedAt()).isNotNull();
        verify(auditLogWriter).write(any(), eq("VISITS.START"), eq(visit.getId()), eq("START"),
                any(), any());
    }

    @Test
    @DisplayName("nothing can be claimed before the planned start")
    void claimBeforeStartIsRefused() {
        Visit visit = confirmedVisit(Instant.now().plus(2, ChronoUnit.HOURS),
                Instant.now().plus(3, ChronoUnit.HOURS));
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        assertThatThrownBy(() -> service.start(visit.getId(), VISITOR))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_START_TOO_EARLY));
        assertThat(visit.getStartedAt()).isNull();
    }

    @Test
    @DisplayName("a claim after the planned end is refused — that visit belongs to the expiry clock")
    void claimAfterEndIsRefused() {
        Visit visit = confirmedVisit(Instant.now().minus(3, ChronoUnit.HOURS),
                Instant.now().minus(2, ChronoUnit.HOURS));
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        assertThatThrownBy(() -> service.start(visit.getId(), VISITOR))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_START_TOO_LATE));
    }

    @Test
    @DisplayName("only a CONFIRMED visit may be claimed started")
    void onlyConfirmedCanStart() {
        Visit visit = confirmedVisit(Instant.now(), Instant.now().plus(30, ChronoUnit.MINUTES));
        visit.setStatus(status(NotraiusStatuses.VISIT_PLANNING));
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        assertThatThrownBy(() -> service.start(visit.getId(), VISITOR))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_INVALID_STATE));
    }

    @Test
    @DisplayName("an outsider cannot touch the pair's evidence")
    void outsiderCannotClaim() {
        Visit visit = confirmedVisit(Instant.now(), Instant.now().plus(30, ChronoUnit.MINUTES));
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        assertThatThrownBy(() -> service.start(visit.getId(), STRANGER))
                .isInstanceOf(ForbiddenException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_START_FORBIDDEN));
    }

    @Test
    @DisplayName("the other party countersigns inside the window, and the claim stands")
    void countersignatureInsideWindow() {
        Visit visit = startedVisit(Instant.now().minus(2, ChronoUnit.MINUTES), VISITOR);
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        var response = service.confirmStart(visit.getId(), ASSIGNEE);

        assertThat(response.getStatusCode()).isEqualTo(NotraiusStatuses.VISIT_STARTED);
        assertThat(visit.getStartConfirmedAt()).isNotNull();
        assertThat(visit.getStartConfirmedBy()).isEqualTo(ASSIGNEE);
        verify(auditLogWriter).write(any(), eq("VISITS.START_CONFIRM"), eq(visit.getId()),
                eq("CONFIRM"), any(), any());
    }

    @Test
    @DisplayName("nobody countersigns their own claim — that is the evidence erased")
    void selfCountersignatureIsRefused() {
        Visit visit = startedVisit(Instant.now().minus(1, ChronoUnit.MINUTES), VISITOR);
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        assertThatThrownBy(() -> service.confirmStart(visit.getId(), VISITOR))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_START_SELF_CONFIRM));
        assertThat(visit.getStartConfirmedAt()).isNull();
    }

    @Test
    @DisplayName("a claim nobody answered inside the window has lapsed")
    void lapsedClaimIsRefused() {
        Visit visit = startedVisit(Instant.now().minus(11, ChronoUnit.MINUTES), VISITOR);
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        assertThatThrownBy(() -> service.confirmStart(visit.getId(), ASSIGNEE))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_START_TOO_LATE));
    }

    @Test
    @DisplayName("confirming an already-confirmed claim changes nothing and writes nothing")
    void alreadyConfirmedIsIdempotent() {
        Visit visit = startedVisit(Instant.now().minus(2, ChronoUnit.MINUTES), VISITOR);
        visit.setStartConfirmedAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        visit.setStartConfirmedBy(ASSIGNEE);
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        service.confirmStart(visit.getId(), ASSIGNEE);

        verify(visitRepository, never()).save(visit);
        verify(auditLogWriter, never()).write(any(), any(), any(), any(), any(), any());
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private Visit confirmedVisit(Instant from, Instant to) {
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
        validity.setToDate(to);

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
        visit.setStatus(status(NotraiusStatuses.VISIT_CONFIRMED));
        visit.setVersion(1);
        return visit;
    }

    private Visit startedVisit(Instant claimedAt, UUID claimer) {
        Visit visit = confirmedVisit(Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now().plus(1, ChronoUnit.HOURS));
        visit.setStatus(status(NotraiusStatuses.VISIT_STARTED));
        visit.setStartedAt(claimedAt);
        visit.setStartedBy(claimer);
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
