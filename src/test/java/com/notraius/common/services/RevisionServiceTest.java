package com.notraius.common.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notraius.common.NotraiusErrors;
import com.notraius.common.dto.responses.RevisionSummaryResponse;
import com.notraius.database.entities.reference.Entities;
import com.notraius.database.repositories.LogsRepository;
import com.notraius.database.repositories.reference.EntitiesRepository;
import com.notraius.modules.asset.pricing.dto.response.PricingResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.primarion.module.errors.AppException;
import org.primarion.module.errors.BadRequestException;
import org.primarion.module.errors.NotFoundException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * The revision reader: one row holds one truth, so a past revision is answered
 * from the audit chain — and REFUSED, never faked, when the chain has no entry.
 */
@ExtendWith(MockitoExtension.class)
class RevisionServiceTest {

    private static final UUID RECORD = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID NAMESPACE = UUID.fromString("66666666-6666-6666-6666-666666666666");

    @Mock private LogsRepository logsRepository;
    @Mock private EntitiesRepository entitiesRepository;

    private RevisionService service;

    @BeforeEach
    void setUp() {
        service = new RevisionService(logsRepository, entitiesRepository,
                new ObjectMapper().findAndRegisterModules());
        Entities entity = new Entities();
        entity.setId(NAMESPACE);
        entity.setCode("PRICINGS");
        lenient().when(entitiesRepository.findByCode("PRICINGS")).thenReturn(Optional.of(entity));
    }

    @Test
    @DisplayName("a retained revision comes back in the live read's own shape")
    void retainedRevisionRoundTrips() {
        when(logsRepository.findRevisionSnapshot(NAMESPACE, RECORD, 2))
                .thenReturn(Optional.of("""
                        {"id":"55555555-5555-5555-5555-555555555555",
                         "amount":300.00,"version":2,"statusCode":"LIVE",
                         "fromDate":"2026-03-01T00:00:00Z"}
                        """));

        PricingResponse revision = service.revisionOf("PRICINGS", RECORD, 2, PricingResponse.class);

        assertThat(revision.getId()).isEqualTo(RECORD);
        assertThat(revision.getAmount()).isEqualByComparingTo(new BigDecimal("300.00"));
        assertThat(revision.getVersion()).isEqualTo(2);
        assertThat(revision.getStatusCode()).isEqualTo("LIVE");
        assertThat(revision.getFromDate()).isEqualTo(Instant.parse("2026-03-01T00:00:00Z"));
    }

    @Test
    @DisplayName("an audit entry enriched with the active delegation still reads back")
    void delegationEnrichmentIsTolerated() {
        // LogsService.withDelegation adds keys the DTO does not declare. The
        // snapshot was written FROM the DTO, so unknown keys are decoration, not
        // a client fault — failing here would make every delegated write
        // unreadable.
        when(logsRepository.findRevisionSnapshot(NAMESPACE, RECORD, 1))
                .thenReturn(Optional.of("""
                        {"id":"55555555-5555-5555-5555-555555555555","amount":250.00,"version":1,
                         "delegatedBy":"77777777-7777-7777-7777-777777777777",
                         "seat":"88888888-8888-8888-8888-888888888888"}
                        """));

        assertThat(service.revisionOf("PRICINGS", RECORD, 1, PricingResponse.class).getAmount())
                .isEqualByComparingTo("250.00");
    }

    @Test
    @DisplayName("a version the chain does not hold is NT-0212, never today's row")
    void missingRevisionIsRefused() {
        when(logsRepository.findRevisionSnapshot(NAMESPACE, RECORD, 9)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revisionOf("PRICINGS", RECORD, 9, PricingResponse.class))
                .isInstanceOf(NotFoundException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.REVISION_NOT_FOUND));
    }

    @Test
    @DisplayName("a server-side parse failure is the same refusal — never a 500 carrying the JSON")
    void unreadableSnapshotIsRefused() {
        when(logsRepository.findRevisionSnapshot(NAMESPACE, RECORD, 3))
                .thenReturn(Optional.of("{not json"));

        assertThatThrownBy(() -> service.revisionOf("PRICINGS", RECORD, 3, PricingResponse.class))
                .isInstanceOf(NotFoundException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.REVISION_NOT_FOUND));
    }

    @Test
    @DisplayName("version 0 or below names no revision at all — a client fault, NT-0211")
    void nonPositiveVersionIsRefused() {
        assertThatThrownBy(() -> service.revisionOf("PRICINGS", RECORD, 0, PricingResponse.class))
                .isInstanceOf(BadRequestException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.REVISION_INVALID));
        assertThatThrownBy(() -> service.revisionOf("PRICINGS", RECORD, -3, PricingResponse.class))
                .isInstanceOf(BadRequestException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.REVISION_INVALID));
    }

    @Test
    @DisplayName("an audit namespace that does not exist is a refusal, not an empty answer")
    void unknownNamespaceIsRefused() {
        when(entitiesRepository.findByCode("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revisionOf("NOPE", RECORD, 1, PricingResponse.class))
                .isInstanceOf(NotFoundException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.REVISION_NOT_FOUND));
    }

    @Test
    @DisplayName("the listing answers every revision the chain holds, the current one flagged")
    void revisionsAreListed() {
        Instant first = Instant.parse("2026-01-01T00:00:00Z");
        Instant second = Instant.parse("2026-02-01T00:00:00Z");
        Instant third = Instant.parse("2026-03-01T00:00:00Z");
        when(logsRepository.findRevisions(NAMESPACE, RECORD)).thenReturn(List.of(
                new LogsRepository.RevisionRow(3, third),
                new LogsRepository.RevisionRow(2, second),
                new LogsRepository.RevisionRow(1, first)));

        List<RevisionSummaryResponse> revisions = service.revisionsOf("PRICINGS", RECORD, 3);

        assertThat(revisions).extracting(RevisionSummaryResponse::getVersion)
                .containsExactly(3, 2, 1);
        assertThat(revisions).extracting(RevisionSummaryResponse::isCurrent)
                .containsExactly(true, false, false);
        assertThat(revisions.getFirst().getRecordedAt()).isEqualTo(third);
    }

    @Test
    @DisplayName("the current revision is flagged even when the chain does not carry it")
    void currentRevisionIsFlaggedEvenWhenAbsentFromTheChain() {
        when(logsRepository.findRevisions(NAMESPACE, RECORD)).thenReturn(List.of(
                new LogsRepository.RevisionRow(1, Instant.parse("2026-01-01T00:00:00Z"))));

        assertThat(service.revisionsOf("PRICINGS", RECORD, 2))
                .extracting(RevisionSummaryResponse::isCurrent)
                .containsExactly(false);
    }

    @Test
    @DisplayName("an unknown namespace refuses the listing instead of answering an empty page")
    void unknownNamespaceRefusesListing() {
        when(entitiesRepository.findByCode("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revisionsOf("NOPE", RECORD, 1))
                .isInstanceOf(NotFoundException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.REVISION_NOT_FOUND));
    }

    private static org.primarion.module.errors.ErrorCode errorOf(Throwable thrown) {
        assertThat(thrown).isInstanceOf(AppException.class);
        return ((AppException) thrown).getError();
    }
}
