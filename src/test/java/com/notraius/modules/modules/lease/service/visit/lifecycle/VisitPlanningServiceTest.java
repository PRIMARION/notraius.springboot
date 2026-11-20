package com.notraius.modules.modules.lease.service.visit.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notraius.common.NotraiusErrors;
import com.notraius.common.services.logs.StatusResolver;
import com.notraius.common.services.visit.VisitTurnReader;
import com.notraius.config.notraius.properties.NotraiusProperties;
import com.notraius.database.entities.asset.asset.Property;
import com.notraius.database.entities.lease.reference.status.Status;
import com.notraius.database.entities.lease.reference.amenity.Validity;
import com.notraius.database.entities.lease.visit.Visit;
import com.notraius.database.entities.lease.visit.VisitPricing;
import com.notraius.database.repositories.lease.visit.VisitRepository;
import com.notraius.modules.core.core.common.constants.NotraiusStatuses;
import com.notraius.modules.lease.visit.visits.dto.request.VisitTimeProposalRequest;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.notraius.database.entities.lease.reference.status.Duration;
import com.notraius.modules.lease.visit.visits.service.VisitService;
import com.notraius.modules.modules.lease.service.visit.workflow.VisitServiceFixture;

/**
 * Stress check for 5.2's turn rule: the move belongs to the counterpart of the
 * last editor, the last editor may refine their own proposal only inside the
 * 30-minute window, nobody confirms their own proposal, and only the two who
 * actually meet may touch the schedule.
 */
@ExtendWith(MockitoExtension.class)
class VisitPlanningServiceTest {

    private static final UUID VISITOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ASSIGNEE = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock private VisitRepository visitRepository;
    @Mock private StatusResolver statusResolver;
    @Mock private VisitTurnReader visitTurnReader;
    @Mock private AuditLogWriter auditLogWriter;

    private VisitService service;

    @BeforeEach
    void setUp() {
        NotraiusProperties properties = new NotraiusProperties();
        properties.setVisitReeditWindowMinutes(30);
        service = new VisitServiceFixture()
                .visitRepository(visitRepository)
                .statusResolver(statusResolver)
                .visitTurnReader(visitTurnReader)
                .properties(properties)
                .auditLogWriter(auditLogWriter)
                .build();
        lenient().when(statusResolver.resolve(any(), any()))
                .thenAnswer(invocation -> status(invocation.getArgument(1)));
    }

    @Test
    @DisplayName("the opening move has no clock — either party may propose first")
    void openingProposalIsFree() {
        Visit visit = planningVisit();
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));
        when(visitTurnReader.lastEdit(visit.getId())).thenReturn(Optional.empty());

        Instant from = Instant.now().plus(3, ChronoUnit.DAYS);
        var response = service.proposeTime(visit.getId(),
                new VisitTimeProposalRequest(from, null), ASSIGNEE);

        assertThat(response.getFromDate()).isEqualTo(from);
        // Duration inherited from the pricing when the proposal omits the end.
        assertThat(response.getToDate()).isEqualTo(from.plus(30, ChronoUnit.MINUTES));
        assertThat(response.getVersion()).isEqualTo(2);
        verify(auditLogWriter).write(any(), eq("VISITS.TIME_PROPOSE"), eq(visit.getId()), eq("PROPOSE"),
                any(), any());
    }

    @Test
    @DisplayName("the counterpart may always counter, with no window")
    void counterpartCountersFreely() {
        Visit visit = planningVisit();
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));
        when(visitTurnReader.lastEdit(visit.getId())).thenReturn(Optional.of(
                new VisitTurnReader.Turn(VISITOR, Instant.now().minus(5, ChronoUnit.HOURS))));

        var response = service.proposeTime(visit.getId(),
                new VisitTimeProposalRequest(Instant.now().plus(4, ChronoUnit.DAYS), null), ASSIGNEE);

        assertThat(response.getVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("the last editor may refine inside the window, and is told when it closed")
    void lastEditorWindow() {
        Visit visit = planningVisit();
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        when(visitTurnReader.lastEdit(visit.getId())).thenReturn(Optional.of(
                new VisitTurnReader.Turn(VISITOR, Instant.now().minus(10, ChronoUnit.MINUTES))));
        assertThat(service.proposeTime(visit.getId(),
                new VisitTimeProposalRequest(Instant.now().plus(5, ChronoUnit.DAYS), null), VISITOR)
                .getVersion()).isEqualTo(2);

        when(visitTurnReader.lastEdit(visit.getId())).thenReturn(Optional.of(
                new VisitTurnReader.Turn(VISITOR, Instant.now().minus(31, ChronoUnit.MINUTES))));
        assertThatThrownBy(() -> service.proposeTime(visit.getId(),
                new VisitTimeProposalRequest(Instant.now().plus(6, ChronoUnit.DAYS), null), VISITOR))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_TURN_CLOSED));
    }

    @Test
    @DisplayName("the counterpart confirms, and PLANNING becomes CONFIRMED")
    void counterpartConfirms() {
        Visit visit = planningVisit();
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));
        when(visitTurnReader.lastEdit(visit.getId())).thenReturn(Optional.of(
                new VisitTurnReader.Turn(VISITOR, Instant.now().minus(2, ChronoUnit.MINUTES))));

        assertThat(service.confirmTime(visit.getId(), ASSIGNEE).getStatusCode())
                .isEqualTo(NotraiusStatuses.VISIT_CONFIRMED);
        verify(auditLogWriter).write(any(), eq("VISITS.TIME_CONFIRM"), eq(visit.getId()), eq("CONFIRM"),
                any(), any());
    }

    @Test
    @DisplayName("the proposer cannot confirm their own proposal — that is self-echo")
    void selfConfirmationIsRefused() {
        Visit visit = planningVisit();
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));
        when(visitTurnReader.lastEdit(visit.getId())).thenReturn(Optional.of(
                new VisitTurnReader.Turn(VISITOR, Instant.now().minus(2, ChronoUnit.MINUTES))));

        assertThatThrownBy(() -> service.confirmTime(visit.getId(), VISITOR))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_SELF_CONFIRM));
    }

    @Test
    @DisplayName("nothing proposed means nothing to confirm")
    void confirmRequiresAProposal() {
        Visit visit = planningVisit();
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));
        when(visitTurnReader.lastEdit(visit.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmTime(visit.getId(), ASSIGNEE))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_TIME_PROPOSAL_MISSING));
    }

    @Test
    @DisplayName("the schedule belongs to the two who meet — not the owner, not a stranger")
    void scheduleBelongsToTheTwo() {
        Visit visit = planningVisit();
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        assertThatThrownBy(() -> service.proposeTime(visit.getId(),
                new VisitTimeProposalRequest(Instant.now().plus(3, ChronoUnit.DAYS), null), OWNER))
                .isInstanceOf(ForbiddenException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_SCHEDULE_FORBIDDEN));
    }

    @Test
    @DisplayName("a window that does not run forwards is refused")
    void invertedWindowIsRefused() {
        Visit visit = planningVisit();
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        Instant from = Instant.now().plus(3, ChronoUnit.DAYS);
        assertThatThrownBy(() -> service.proposeTime(visit.getId(),
                new VisitTimeProposalRequest(from, from.minus(1, ChronoUnit.HOURS)), VISITOR))
                .isInstanceOf(AppException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_WINDOW_INVALID));
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private Visit planningVisit() {
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
        validity.setToDate(Instant.now().plus(2, ChronoUnit.DAYS).plus(30, ChronoUnit.MINUTES));

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
        visit.setStatus(status(NotraiusStatuses.VISIT_PLANNING));
        visit.setVersion(1);
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
