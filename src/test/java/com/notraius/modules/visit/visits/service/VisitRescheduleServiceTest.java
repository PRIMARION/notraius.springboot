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
import com.notraius.database.repositories.LogsRepository;
import com.notraius.database.repositories.visit.VisitRepository;
import com.notraius.modules.core.common.constants.NotraiusStatuses;
import com.notraius.modules.visit.visits.dto.request.VisitRescheduleRequest;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 5.4's clean door: no money, no judgment, one per person, at least the horizon
 * away, and the same turn rule as a first planning.
 */
@ExtendWith(MockitoExtension.class)
class VisitRescheduleServiceTest {

    private static final UUID VISITOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ASSIGNEE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ACTIVITY_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Mock private VisitRepository visitRepository;
    @Mock private StatusResolver statusResolver;
    @Mock private PayPaymentClient payPaymentClient;
    @Mock private AuditLogWriter auditLogWriter;
    @Mock private LogsRepository logsRepository;
    @Mock private ActivityRepository activityRepository;

    private VisitService service;
    private NotraiusProperties properties;

    @BeforeEach
    void setUp() {
        properties = new NotraiusProperties();
        properties.setVisitCancelHorizonHours(24);
        VisitLedgerService ledger = new VisitLedgerService(visitRepository, payPaymentClient,
                statusResolver, new VisitMapper(), new AuditTrail(new ObjectMapper().findAndRegisterModules(), auditLogWriter),
                null, null, new ObjectMapper().findAndRegisterModules());
        service = new VisitServiceFixture()
                .visitRepository(visitRepository)
                .statusResolver(statusResolver)
                .visitLedgerService(ledger)
                .logsRepository(logsRepository)
                .activityRepository(activityRepository)
                .properties(properties)
                .build();
        lenient().when(statusResolver.resolve(any(), any()))
                .thenAnswer(invocation -> status(invocation.getArgument(1)));
        lenient().when(activityRepository.findByCode("VISITS.RESCHEDULE"))
                .thenReturn(Optional.of(activity()));
    }

    @Test
    @DisplayName("a legal reschedule re-enters PLANNING with the new time and moves no money")
    void rescheduleReentersPlanning() {
        Visit visit = confirmedVisit(Instant.now().plus(3, ChronoUnit.DAYS));
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));
        when(logsRepository.countByReferenceActivityAndCreator(visit.getId(), ACTIVITY_ID, VISITOR))
                .thenReturn(0L);

        Instant newFrom = Instant.now().plus(4, ChronoUnit.DAYS);
        var response = service.reschedule(visit.getId(), new VisitRescheduleRequest(newFrom, null),
                VISITOR);

        assertThat(response.getStatusCode()).isEqualTo(NotraiusStatuses.VISIT_PLANNING);
        assertThat(response.getFromDate()).isEqualTo(newFrom);
        // The pricing's 30 minutes decides the end when the caller omits it.
        assertThat(response.getToDate()).isEqualTo(newFrom.plus(30, ChronoUnit.MINUTES));
        verify(auditLogWriter).write(any(), eq("VISITS.RESCHEDULE"), eq(visit.getId()),
                eq("RESCHEDULE"), any(), any());
        verifyNoInteractions(payPaymentClient);
    }

    @Test
    @DisplayName("inside the horizon neither self-serve door works")
    void insideHorizonIsRefused() {
        Visit visit = confirmedVisit(Instant.now().plus(2, ChronoUnit.HOURS));
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        assertThatThrownBy(() -> service.reschedule(visit.getId(),
                new VisitRescheduleRequest(Instant.now().plus(5, ChronoUnit.DAYS), null), VISITOR))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t))
                        .isEqualTo(NotraiusErrors.VISIT_RESCHEDULE_WINDOW_CLOSED));
    }

    @Test
    @DisplayName("one reschedule per person: the second attempt names the closed door")
    void secondRescheduleIsRefused() {
        Visit visit = confirmedVisit(Instant.now().plus(3, ChronoUnit.DAYS));
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));
        when(logsRepository.countByReferenceActivityAndCreator(visit.getId(), ACTIVITY_ID, VISITOR))
                .thenReturn(1L);

        assertThatThrownBy(() -> service.reschedule(visit.getId(),
                new VisitRescheduleRequest(Instant.now().plus(4, ChronoUnit.DAYS), null), VISITOR))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_RESCHEDULE_LIMIT));
    }

    @Test
    @DisplayName("the counterpart's own request is untouched by the visitor's use of theirs")
    void eachPartyHasTheirOwnDoor() {
        Visit visit = confirmedVisit(Instant.now().plus(3, ChronoUnit.DAYS));
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));
        when(logsRepository.countByReferenceActivityAndCreator(visit.getId(), ACTIVITY_ID, ASSIGNEE))
                .thenReturn(0L);

        var response = service.reschedule(visit.getId(),
                new VisitRescheduleRequest(Instant.now().plus(5, ChronoUnit.DAYS), null), ASSIGNEE);

        assertThat(response.getStatusCode()).isEqualTo(NotraiusStatuses.VISIT_PLANNING);
    }

    @Test
    @DisplayName("the schedule belongs to the two who meet — not to the owner")
    void ownerCannotReschedule() {
        Visit visit = confirmedVisit(Instant.now().plus(3, ChronoUnit.DAYS));
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        assertThatThrownBy(() -> service.reschedule(visit.getId(),
                new VisitRescheduleRequest(Instant.now().plus(4, ChronoUnit.DAYS), null), OWNER))
                .isInstanceOf(ForbiddenException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_SCHEDULE_FORBIDDEN));
    }

    @Test
    @DisplayName("only a CONFIRMED visit is rescheduled — the rest are still being agreed")
    void onlyConfirmedCanReschedule() {
        Visit visit = confirmedVisit(Instant.now().plus(3, ChronoUnit.DAYS));
        visit.setStatus(status(NotraiusStatuses.VISIT_PLANNING));
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        assertThatThrownBy(() -> service.reschedule(visit.getId(),
                new VisitRescheduleRequest(Instant.now().plus(4, ChronoUnit.DAYS), null), VISITOR))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_INVALID_STATE));
    }

    @Test
    @DisplayName("a window that does not run forwards is refused, not stored")
    void invertedWindowIsRefused() {
        Visit visit = confirmedVisit(Instant.now().plus(3, ChronoUnit.DAYS));
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));
        when(logsRepository.countByReferenceActivityAndCreator(visit.getId(), ACTIVITY_ID, VISITOR))
                .thenReturn(0L);

        Instant from = Instant.now().plus(4, ChronoUnit.DAYS);
        assertThatThrownBy(() -> service.reschedule(visit.getId(),
                new VisitRescheduleRequest(from, from.minus(1, ChronoUnit.HOURS)), VISITOR))
                .isInstanceOf(AppException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_WINDOW_INVALID));
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private Visit confirmedVisit(Instant from) {
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
        visit.setStatus(status(NotraiusStatuses.VISIT_CONFIRMED));
        visit.setVersion(3);
        return visit;
    }

    private Activity activity() {
        Activity activity = new Activity();
        activity.setId(ACTIVITY_ID);
        activity.setCode("VISITS.RESCHEDULE");
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
