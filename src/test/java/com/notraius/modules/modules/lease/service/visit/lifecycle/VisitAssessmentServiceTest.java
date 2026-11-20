package com.notraius.modules.modules.lease.service.visit.lifecycle;

import com.notraius.common.NotraiusErrors;
import com.notraius.common.integration.pay.ReputationAssessmentClient;
import com.notraius.config.notraius.properties.NotraiusProperties;
import com.notraius.database.entities.asset.asset.Property;
import com.notraius.database.entities.lease.reference.status.Status;
import com.notraius.database.entities.lease.reference.amenity.Validity;
import com.notraius.database.entities.lease.visit.Visit;
import com.notraius.database.entities.lease.visit.VisitPricing;
import com.notraius.database.repositories.lease.visit.VisitRepository;
import com.notraius.modules.core.core.common.constants.NotraiusStatuses;
import com.notraius.modules.lease.visit.visits.dto.request.visit.VisitAssessmentCreateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.primarion.module.app.access.consent.database.Activity;
import org.primarion.module.app.access.consent.database.ActivityRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.notraius.modules.lease.visit.visits.service.VisitService;
import com.notraius.modules.modules.lease.service.visit.workflow.VisitServiceFixture;

/**
 * 5.3's mutual judgment: who judges whom is fixed by the visit, one filing per
 * party, and the confirm gate reads the host's list rather than any local flag.
 */
@ExtendWith(MockitoExtension.class)
class VisitAssessmentServiceTest {

    private static final UUID VISITOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ASSIGNEE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID STRANGER = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID ACTIVITY_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Mock private VisitRepository visitRepository;
    @Mock private ReputationAssessmentClient assessmentClient;
    @Mock private ActivityRepository activityRepository;

    private VisitService service;

    @BeforeEach
    void setUp() {
        NotraiusProperties properties = new NotraiusProperties();
        properties.setVisitAssessmentWindowMinutes(60);
        service = new VisitServiceFixture()
                .visitRepository(visitRepository)
                .assessmentClient(assessmentClient)
                .activityRepository(activityRepository)
                .properties(properties)
                .build();
    }

    @Test
    @DisplayName("the visitor's judgment is filed against the property owner, never the assignee")
    void visitorJudgesTheOwner() {
        Visit visit = startedVisit();
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));
        when(assessmentClient.listForVisit(visit.getId())).thenReturn(List.of());
        when(activityRepository.findByCode("VISITS.ASSESS")).thenReturn(Optional.of(activity()));
        Map<String, BigDecimal> axes = Map.of("PUNCTUALITY", new BigDecimal("4"), "CARE", new BigDecimal("5"));
        when(assessmentClient.submit(eq(OWNER), eq(VISITOR), eq(visit.getId()), eq("VISITS.ASSESS"),
                eq(ACTIVITY_ID), eq("Lovely place"), eq(axes)))
                .thenReturn(assessment(VISITOR, "Lovely place", axes));

        var response = service.assess(visit.getId(),
                new VisitAssessmentCreateRequest(axes, "Lovely place"), VISITOR);

        assertThat(response.getAuthorId()).isEqualTo(VISITOR);
        assertThat(response.getAxisScores()).containsEntry("CARE", new BigDecimal("5"));
    }

    @Test
    @DisplayName("the property's side judges the visitor — the assignee is the one who was there")
    void ownerSideJudgesTheVisitor() {
        Visit visit = startedVisit();
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));
        when(assessmentClient.listForVisit(visit.getId())).thenReturn(List.of());
        when(activityRepository.findByCode("VISITS.ASSESS")).thenReturn(Optional.of(activity()));
        Map<String, BigDecimal> axes = Map.of("PUNCTUALITY", new BigDecimal("2"));
        when(assessmentClient.submit(eq(VISITOR), eq(ASSIGNEE), eq(visit.getId()), eq("VISITS.ASSESS"),
                eq(ACTIVITY_ID), any(), eq(axes)))
                .thenReturn(assessment(ASSIGNEE, null, axes));

        service.assess(visit.getId(), new VisitAssessmentCreateRequest(axes, null), ASSIGNEE);

        verify(assessmentClient).submit(eq(VISITOR), eq(ASSIGNEE), eq(visit.getId()),
                eq("VISITS.ASSESS"), eq(ACTIVITY_ID), any(), eq(axes));
    }

    @Test
    @DisplayName("a stranger to the visit may not judge anyone")
    void strangerIsRefused() {
        Visit visit = startedVisit();
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        assertThatThrownBy(() -> service.assess(visit.getId(),
                new VisitAssessmentCreateRequest(Map.of("PUNCTUALITY", BigDecimal.ONE), null), STRANGER))
                .isInstanceOf(ForbiddenException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_ASSESSMENT_FORBIDDEN));
    }

    @Test
    @DisplayName("judging begins only once the visit has started")
    void assessmentRequiresStart() {
        Visit visit = startedVisit();
        visit.setStatus(status(NotraiusStatuses.VISIT_CONFIRMED));
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        assertThatThrownBy(() -> service.assess(visit.getId(),
                new VisitAssessmentCreateRequest(Map.of("PUNCTUALITY", BigDecimal.ONE), null), VISITOR))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_NOT_STARTED));
    }

    @Test
    @DisplayName("after the assessment hour the door seals for that visit")
    void windowClosesAfterTheHour() {
        Visit visit = startedVisit();
        visit.getValidity().setToDate(Instant.now().minus(61, ChronoUnit.MINUTES));
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        assertThatThrownBy(() -> service.assess(visit.getId(),
                new VisitAssessmentCreateRequest(Map.of("PUNCTUALITY", BigDecimal.ONE), null), VISITOR))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_ASSESSMENT_WINDOW_CLOSED));
    }

    @Test
    @DisplayName("a filed judgment stands; a second submission is refused")
    void secondSubmissionIsRefused() {
        Visit visit = startedVisit();
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));
        when(assessmentClient.listForVisit(visit.getId()))
                .thenReturn(List.of(assessment(VISITOR, null, Map.of("PUNCTUALITY", BigDecimal.ONE))));

        assertThatThrownBy(() -> service.assess(visit.getId(),
                new VisitAssessmentCreateRequest(Map.of("PUNCTUALITY", BigDecimal.ONE), null), VISITOR))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_ALREADY_ASSESSED));
        verify(assessmentClient, never()).submit(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("the gate opens only when both sides appear on the host's list")
    void gateNeedsBothSides() {
        Visit visit = startedVisit();
        when(assessmentClient.listForVisit(visit.getId()))
                .thenReturn(List.of(assessment(VISITOR, null, Map.of("PUNCTUALITY", BigDecimal.ONE))));
        assertThat(service.bothAssessed(visit.getId(), visit)).isFalse();

        when(assessmentClient.listForVisit(visit.getId()))
                .thenReturn(List.of(
                        assessment(VISITOR, null, Map.of("PUNCTUALITY", BigDecimal.ONE)),
                        assessment(ASSIGNEE, null, Map.of("PUNCTUALITY", BigDecimal.ONE))));
        assertThat(service.bothAssessed(visit.getId(), visit)).isTrue();
    }

    @Test
    @DisplayName("reading the judgments belongs to the parties too")
    void readingBelongsToTheParties() {
        Visit visit = startedVisit();
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        assertThatThrownBy(() -> service.assessments(visit.getId(), STRANGER))
                .isInstanceOf(ForbiddenException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_ASSESSMENT_FORBIDDEN));
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
        validity.setFromDate(Instant.now().minus(10, ChronoUnit.MINUTES));
        validity.setToDate(Instant.now().minus(5, ChronoUnit.MINUTES));

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
        visit.setStartedAt(Instant.now().minus(10, ChronoUnit.MINUTES));
        visit.setStartedBy(VISITOR);
        visit.setVersion(2);
        return visit;
    }

    private Activity activity() {
        Activity activity = new Activity();
        activity.setId(ACTIVITY_ID);
        activity.setCode("VISITS.ASSESS");
        return activity;
    }

    private ReputationAssessmentClient.AssessmentRef assessment(UUID author, String reason,
                                                                Map<String, BigDecimal> axes) {
        return new ReputationAssessmentClient.AssessmentRef(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), ACTIVITY_ID, author, new BigDecimal("4"), reason, axes, Instant.now());
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
