package com.notraius.modules.modules.lease.service.visit.requests;

import com.notraius.common.NotraiusErrors;
import com.notraius.database.entities.lease.reference.status.Status;
import com.notraius.database.entities.lease.reference.amenity.Validity;
import com.notraius.database.entities.lease.visit.Visit;
import com.notraius.database.repositories.lease.visit.VisitRepository;
import com.notraius.modules.core.core.common.constants.NotraiusStatuses;
import com.notraius.modules.lease.visit.visits.dto.response.VisitResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.primarion.module.communication.errors.exception.AppException;
import org.primarion.module.communication.errors.client.BadRequestException;
import org.primarion.module.communication.errors.core.ErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.notraius.config.notraius.properties.NotraiusProperties;
import com.notraius.modules.lease.visit.visits.service.VisitNextActions;
import com.notraius.modules.lease.visit.visits.service.VisitService;
import com.notraius.modules.modules.lease.service.visit.workflow.VisitServiceFixture;
import java.time.temporal.ChronoUnit;

/**
 * The two list doors (PLAN 5.1–5.5) as a user walks them: "what is still
 * happening", "what is coming up", "what is waiting for me".
 * <p>
 * The laws checked here are the ones a client would otherwise have to guess: a
 * missing filter means <em>every</em> status (not an empty page), a status it
 * names must exist, an inverted window is a refusal rather than an empty answer,
 * and the order is a whitelist — {@code recent} (the historical answer) or
 * {@code window} (soonest first).
 */
@ExtendWith(MockitoExtension.class)
class VisitListingServiceTest {

    private static final UUID CALLER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    /** The open end of the service's window filter. */
    private static final Instant FOREVER = Instant.parse("9999-12-31T23:59:59Z");

    @Mock
    private VisitRepository visitRepository;

    @Test
    @DisplayName("no status filter means every status, so SQL keeps one indexed IN")
    void unfilteredListAsksForEveryStatus() {
        VisitService service = service();
        when(visitRepository.searchAsVisitor(eq(CALLER), any(), any(), any(), any())).thenReturn(Page.empty());

        service.listMyVisits(CALLER, null, null, null, PageRequest.of(0, 20));

        ArgumentCaptor<List<String>> statuses = statusCaptor();
        verify(visitRepository).searchAsVisitor(eq(CALLER), statuses.capture(),
                eq(Instant.EPOCH), eq(FOREVER), any());
        assertThat(statuses.getValue()).containsExactly(
                NotraiusStatuses.VISIT_UNPAID,
                NotraiusStatuses.VISIT_REQUESTED,
                NotraiusStatuses.VISIT_ASSIGNED,
                NotraiusStatuses.VISIT_PLANNING,
                NotraiusStatuses.VISIT_CONFIRMED,
                NotraiusStatuses.VISIT_STARTED,
                NotraiusStatuses.VISIT_COMPLETED,
                NotraiusStatuses.VISIT_EXPIRED,
                NotraiusStatuses.VISIT_CANCELLED);
    }

    @Test
    @DisplayName("a status filter is case-tolerant and de-duplicated")
    void statusFilterIsNormalised() {
        VisitService service = service();
        when(visitRepository.searchAsVisitor(eq(CALLER), any(), any(), any(), any())).thenReturn(Page.empty());

        service.listMyVisits(CALLER, List.of("confirmed", " CONFIRMED ", "Expired"), null, null,
                PageRequest.of(0, 20));

        ArgumentCaptor<List<String>> statuses = statusCaptor();
        verify(visitRepository).searchAsVisitor(eq(CALLER), statuses.capture(), any(), any(), any());
        assertThat(statuses.getValue()).containsExactly(
                NotraiusStatuses.VISIT_CONFIRMED, NotraiusStatuses.VISIT_EXPIRED);
    }

    @Test
    @DisplayName("a status that is not a visit status is refused by name")
    void unknownStatusIsRefused() {
        VisitService service = service();

        assertThatThrownBy(() -> service.listMyVisits(CALLER, List.of("PAID"), null, null,
                PageRequest.of(0, 20)))
                .isInstanceOf(BadRequestException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_FILTER_INVALID));
    }

    @Test
    @DisplayName("an inverted window is a refusal, not an empty page")
    void invertedWindowIsRefused() {
        VisitService service = service();
        Instant now = Instant.now();

        assertThatThrownBy(() -> service.listMyVisits(CALLER, null, now, now.minusSeconds(60),
                PageRequest.of(0, 20)))
                .isInstanceOf(BadRequestException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_FILTER_INVALID));
    }

    @Test
    @DisplayName("the counterpart's page is filtered the same way and reads the owner's inbox")
    void assignedPageCarriesTheFilters() {
        VisitService service = service();
        Instant from = Instant.now();
        Page<Visit> empty = new PageImpl<>(List.of());
        when(visitRepository.searchAsParty(eq(CALLER), any(), eq(from), eq(FOREVER), any())).thenReturn(empty);

        Page<?> page = service.listAssignedVisits(CALLER, List.of("REQUESTED"), from, null,
                PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isZero();
        ArgumentCaptor<List<String>> statuses = statusCaptor();
        verify(visitRepository).searchAsParty(eq(CALLER), statuses.capture(), eq(from), eq(FOREVER), any());
        assertThat(statuses.getValue()).containsExactly(NotraiusStatuses.VISIT_REQUESTED);
    }

    @Test
    @DisplayName("the order is a whitelist: recent stays the default, window is soonest first")
    void sortIsAWhitelist() {
        Sort recent = VisitService.sortOf(null);
        assertThat(recent.getOrderFor("createdAt")).isNotNull();
        assertThat(recent.getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(VisitService.sortOf("recent")).isEqualTo(recent);

        Sort window = VisitService.sortOf("window");
        assertThat(window.getOrderFor("validity.fromDate")).isNotNull();
        assertThat(window.getOrderFor("validity.fromDate").getDirection()).isEqualTo(Sort.Direction.ASC);

        assertThatThrownBy(() -> VisitService.sortOf("cheapest"))
                .isInstanceOf(BadRequestException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_FILTER_INVALID));
    }

    @Test
    @DisplayName("each row on the page carries the caller's next act, computed from the row itself")
    void pageCarriesTheCallersNextActs() {
        Visit request = new Visit();
        request.setId(UUID.randomUUID());
        request.setVisitorId(CALLER);
        request.setOwnerId(UUID.randomUUID());
        request.setStatus(status(NotraiusStatuses.VISIT_REQUESTED));
        Validity validity = new Validity();
        validity.setFromDate(Instant.now().plus(2, java.time.temporal.ChronoUnit.DAYS));
        validity.setToDate(Instant.now().plus(2, java.time.temporal.ChronoUnit.DAYS)
                .plus(1, java.time.temporal.ChronoUnit.HOURS));
        request.setValidity(validity);

        VisitService service = service();
        when(visitRepository.searchAsVisitor(eq(CALLER), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(request)));

        Page<VisitResponse> page = service.listMyVisits(CALLER, null, null, null,
                PageRequest.of(0, 20));

        assertThat(page.getContent().getFirst().getNextActions())
                .containsExactly(VisitNextActions.CANCEL);
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private VisitService service() {
        return new VisitServiceFixture()
                .visitRepository(visitRepository)
                .properties(new com.notraius.config.notraius.properties.NotraiusProperties())
                .build();
    }

    private static Status status(String code) {
        Status status = new Status();
        status.setId(UUID.randomUUID());
        status.setCode(code);
        return status;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<List<String>> statusCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }

    private static ErrorCode errorOf(Throwable thrown) {
        assertThat(thrown).isInstanceOf(AppException.class);
        return ((AppException) thrown).getError();
    }
}
