package com.notraius.modules.visit.visits.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notraius.common.NotraiusErrors;
import com.notraius.common.integration.PayPaymentClient;
import com.notraius.common.services.StatusResolver;
import com.notraius.database.entities.asset.Property;
import com.notraius.database.entities.reference.Status;
import com.notraius.database.entities.reference.Validity;
import com.notraius.database.entities.visit.Visit;
import com.notraius.database.entities.visit.VisitPricing;
import com.notraius.database.repositories.visit.VisitRepository;
import com.notraius.modules.core.common.constants.NotraiusStatuses;
import com.notraius.modules.visit.visits.dto.request.VisitAssignRequest;
import com.notraius.modules.visit.visits.dto.response.VisitResponse;
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
import org.primarion.module.utils.security.identity.ResolvedUser;
import org.primarion.module.utils.security.users.UserClient;

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
 * Stress check for 5.2's acceptance: the stamp names a person who must accept in
 * their own name, responsibility may still change hands in ASSIGNED and never
 * after, a reject undoes the stamp, and the owner's decline before acceptance
 * pays the visitor back in full — because nothing was committed to.
 */
@ExtendWith(MockitoExtension.class)
class VisitAcceptanceServiceTest {

    private static final UUID VISITOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ASSIGNEE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID COLLEAGUE = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID STRANGER = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Mock private VisitRepository visitRepository;
    @Mock private PayPaymentClient payPaymentClient;
    @Mock private StatusResolver statusResolver;
    @Mock private UserClient userClient;
    @Mock private AuditLogWriter auditLogWriter;

    private VisitService service;

    @BeforeEach
    void setUp() {
        service = new VisitServiceFixture()
                .visitRepository(visitRepository)
                .payPaymentClient(payPaymentClient)
                .statusResolver(statusResolver)
                .userClient(userClient)
                .auditLogWriter(auditLogWriter)
                .build();
        lenient().when(statusResolver.resolve(any(), any()))
                .thenAnswer(invocation -> status(invocation.getArgument(1)));
    }

    // ── The stamp ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("the stamp moves REQUESTED to ASSIGNED and names the human")
    void assignMovesToAssigned() {
        Visit visit = visit(NotraiusStatuses.VISIT_REQUESTED, null);
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));
        when(userClient.resolveById(ASSIGNEE)).thenReturn(present(ASSIGNEE));

        VisitResponse response = service.assign(visit.getId(), new VisitAssignRequest(ASSIGNEE), OWNER);

        assertThat(response.getStatusCode()).isEqualTo(NotraiusStatuses.VISIT_ASSIGNED);
        assertThat(response.getAssigneeId()).isEqualTo(ASSIGNEE);
        assertThat(response.getVersion()).isEqualTo(2);
        verify(auditLogWriter).write(any(), eq("VISITS.ASSIGN"), eq(visit.getId()), eq("ASSIGN"), any(), any());
    }

    @Test
    @DisplayName("responsibility may change hands again while ASSIGNED")
    void reassignIsLegalWhileAssigned() {
        Visit visit = visit(NotraiusStatuses.VISIT_ASSIGNED, ASSIGNEE);
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));
        when(userClient.resolveById(COLLEAGUE)).thenReturn(present(COLLEAGUE));

        VisitResponse response = service.assign(visit.getId(), new VisitAssignRequest(COLLEAGUE), OWNER);

        assertThat(response.getAssigneeId()).isEqualTo(COLLEAGUE);
        assertThat(response.getStatusCode()).isEqualTo(NotraiusStatuses.VISIT_ASSIGNED);
        verify(auditLogWriter).write(any(), eq("VISITS.ASSIGN"), eq(visit.getId()), eq("REASSIGN"), any(), any());
    }

    @Test
    @DisplayName("the stamp cannot name the visitor or the owner — it names someone else")
    void assigneeMustBeSomeoneElse() {
        Visit visit = visit(NotraiusStatuses.VISIT_REQUESTED, null);
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        assertThatThrownBy(() -> service.assign(visit.getId(), new VisitAssignRequest(OWNER), OWNER))
                .isInstanceOf(AppException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_ASSIGNEE_INVALID));

        assertThatThrownBy(() -> service.assign(visit.getId(), new VisitAssignRequest(VISITOR), OWNER))
                .isInstanceOf(AppException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_ASSIGNEE_INVALID));
    }

    @Test
    @DisplayName("an unknown user cannot be stamped, and unreachable identity fails closed")
    void assigneeMustExist() {
        Visit visit = visit(NotraiusStatuses.VISIT_REQUESTED, null);
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));
        when(userClient.resolveById(ASSIGNEE)).thenReturn(UserClient.Resolution.absent());

        assertThatThrownBy(() -> service.assign(visit.getId(), new VisitAssignRequest(ASSIGNEE), OWNER))
                .isInstanceOf(AppException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_ASSIGNEE_INVALID));

        when(userClient.resolveById(COLLEAGUE)).thenReturn(UserClient.Resolution.unreachable());
        assertThatThrownBy(() -> service.assign(visit.getId(), new VisitAssignRequest(COLLEAGUE), OWNER))
                .isInstanceOf(ForbiddenException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_ASSIGNEE_UNREACHABLE));
    }

    @Test
    @DisplayName("only the owner stamps, and only before acceptance")
    void assignGates() {
        Visit visit = visit(NotraiusStatuses.VISIT_REQUESTED, null);
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        assertThatThrownBy(() -> service.assign(visit.getId(), new VisitAssignRequest(ASSIGNEE), STRANGER))
                .isInstanceOf(ForbiddenException.class);

        Visit planning = visit(NotraiusStatuses.VISIT_PLANNING, ASSIGNEE);
        when(visitRepository.findById(planning.getId())).thenReturn(Optional.of(planning));
        assertThatThrownBy(() -> service.assign(planning.getId(), new VisitAssignRequest(COLLEAGUE), OWNER))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_INVALID_STATE));
    }

    // ── Acceptance ────────────────────────────────────────────────────────

    @Test
    @DisplayName("the stamped human accepts in their own person: ASSIGNED → PLANNING")
    void acceptMovesToPlanning() {
        Visit visit = visit(NotraiusStatuses.VISIT_ASSIGNED, ASSIGNEE);
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        assertThat(service.accept(visit.getId(), ASSIGNEE).getStatusCode())
                .isEqualTo(NotraiusStatuses.VISIT_PLANNING);
        verify(auditLogWriter).write(any(), eq("VISITS.ACCEPT"), eq(visit.getId()), eq("ACCEPT"), any(), any());
    }

    @Test
    @DisplayName("nobody accepts a stamp that is not theirs")
    void acceptIsTheAssigneesAlone() {
        Visit visit = visit(NotraiusStatuses.VISIT_ASSIGNED, ASSIGNEE);
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        assertThatThrownBy(() -> service.accept(visit.getId(), OWNER))
                .isInstanceOf(ForbiddenException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_ASSIGNEE_MISMATCH));
    }

    @Test
    @DisplayName("reject undoes the stamp: back to REQUESTED, the request live again")
    void rejectReturnsToRequested() {
        Visit visit = visit(NotraiusStatuses.VISIT_ASSIGNED, ASSIGNEE);
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        VisitResponse response = service.reject(visit.getId(), ASSIGNEE);

        assertThat(response.getStatusCode()).isEqualTo(NotraiusStatuses.VISIT_REQUESTED);
        assertThat(response.getAssigneeId()).isNull();
    }

    // ── The owner's decline ───────────────────────────────────────────────

    @Test
    @DisplayName("declining before acceptance refunds the visitor in full and ends the visit")
    void declineRefundsInFull() {
        Visit visit = visit(NotraiusStatuses.VISIT_ASSIGNED, ASSIGNEE);
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        VisitResponse response = service.decline(visit.getId(), OWNER);

        assertThat(response.getStatusCode()).isEqualTo(NotraiusStatuses.VISIT_CANCELLED);
        assertThat(response.getAssigneeId()).isNull();
        verify(payPaymentClient).refund(visit.getPaymentId());
        verify(auditLogWriter).write(any(), eq("VISITS.DECLINE"), eq(visit.getId()), eq("DECLINE"), any(), any());
    }

    @Test
    @DisplayName("a failed refund leaves the visit untouched — money is never abandoned")
    void failedRefundDoesNotCancel() {
        Visit visit = visit(NotraiusStatuses.VISIT_REQUESTED, null);
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));
        org.mockito.Mockito.doThrow(new IllegalStateException("pay down"))
                .when(payPaymentClient).refund(any());

        assertThatThrownBy(() -> service.decline(visit.getId(), OWNER))
                .isInstanceOf(IllegalStateException.class);
        verify(visitRepository, never()).save(any());
    }

    @Test
    @DisplayName("a confirmed visit cannot be declined through this door")
    void declineClosesAtPlanning() {
        Visit visit = visit(NotraiusStatuses.VISIT_CONFIRMED, ASSIGNEE);
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        assertThatThrownBy(() -> service.decline(visit.getId(), OWNER))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_INVALID_STATE));
        verify(payPaymentClient, never()).refund(any());
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private Visit visit(String statusCode, UUID assignee) {
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
        visit.setUserId(assignee);
        visit.setOwnerId(OWNER);
        visit.setPricing(pricing);
        visit.setValidity(validity);
        visit.setAmount(new BigDecimal("25.00"));
        visit.setCurrencyId(property.getCurrencyId());
        visit.setPaymentId(UUID.randomUUID());
        visit.setPaymentSplitId(UUID.randomUUID());
        visit.setStatus(status(statusCode));
        visit.setVersion(1);
        return visit;
    }

    private UserClient.Resolution present(UUID userId) {
        return new UserClient.Resolution(Optional.of(new ResolvedUser(userId, "Assignee", "a@example.com")),
                true);
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
