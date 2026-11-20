package com.notraius.modules.services;

import com.notraius.common.NotraiusErrors;
import com.notraius.database.entities.lease.reference.amenity.Entities;
import com.notraius.database.entities.lease.reference.status.Status;
import com.notraius.database.repositories.lease.reference.duration.EntitiesRepository;
import com.notraius.database.repositories.lease.reference.duration.StatusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.primarion.module.communication.errors.exception.AppException;
import org.primarion.module.communication.errors.client.BadRequestException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import org.primarion.module.communication.errors.core.ErrorCode;
import com.notraius.common.services.visit.QueryFilters;

/**
 * The read doors' filter law: a mistyped filter is refused by name, never
 * answered with an empty page that reads as "you have nothing".
 */
@ExtendWith(MockitoExtension.class)
public class QueryFiltersTest {

    @Mock private StatusRepository statusRepository;
    @Mock private EntitiesRepository entitiesRepository;

    private QueryFilters filters;

    @BeforeEach
    public void setUp() {
        filters = new QueryFilters(statusRepository, entitiesRepository);
    }

    // ── Statuses ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("a real status is canonicalised, not echoed: ' published ' becomes PUBLISHED")
    public void statusIsCanonicalised() {
        when(statusRepository.findByCodeAndEntityCode("PUBLISHED", "PROPERTY"))
                .thenReturn(Optional.of(status("PUBLISHED")));

        assertThat(filters.status("PROPERTY", " published ")).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("a status from ANOTHER entity's world is refused — namespaces never bleed")
    public void foreignStatusIsRefused() {
        // PUBLISHED is a PROPERTY status; asking for it on the rules page is a
        // typo the catalogue must answer, not a filter that matches nothing.
        when(statusRepository.findByCodeAndEntityCode("PUBLISHED", "RULES")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> filters.status("RULES", "PUBLISHED"))
                .isInstanceOf(BadRequestException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.FILTER_INVALID));
    }

    @Test
    @DisplayName("no filter is no filter: null and blank both mean 'every status'")
    public void blankStatusIsNoFilter() {
        assertThat(filters.status("PROPERTY", null)).isNull();
        assertThat(filters.status("PROPERTY", "   ")).isNull();
    }

    // ── Entity namespaces ─────────────────────────────────────────────────

    @Test
    @DisplayName("a registered entity namespace is accepted, upper-cased")
    public void entityIsValidated() {
        Entities entity = new Entities();
        entity.setId(UUID.randomUUID());
        entity.setCode("PRICINGS");
        when(entitiesRepository.findByCode("PRICINGS")).thenReturn(Optional.of(entity));

        assertThat(filters.entity(" pricings ")).isEqualTo("PRICINGS");
    }

    @Test
    @DisplayName("an unknown entity namespace is refused")
    public void unknownEntityIsRefused() {
        when(entitiesRepository.findByCode("PROPERTIES")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> filters.entity("PROPERTIES"))
                .isInstanceOf(BadRequestException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.FILTER_INVALID));
    }

    // ── Free text ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("a search is trimmed and its inner whitespace collapsed into one term")
    public void searchIsNormalised() {
        assertThat(filters.search("  two   bedrooms\nnear  market ")).isEqualTo("two bedrooms near market");
    }

    @Test
    @DisplayName("blank text is no filter, not the empty string")
    public void blankSearchIsNoFilter() {
        assertThat(filters.search(null)).isNull();
        assertThat(filters.search(" \t\n ")).isNull();
    }

    @Test
    @DisplayName("the cap is a refusal, not a silent truncation: 201 characters is NT-0210")
    public void overlongSearchIsRefused() {
        String tooLong = "a".repeat(QueryFilters.MAX_SEARCH_LENGTH + 1);

        assertThatThrownBy(() -> filters.search(tooLong))
                .isInstanceOf(BadRequestException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.FILTER_INVALID));
    }

    @Test
    @DisplayName("exactly at the cap is accepted — the bound is inclusive")
    public void searchAtTheCapIsAccepted() {
        String atCap = "a".repeat(QueryFilters.MAX_SEARCH_LENGTH);

        assertThat(filters.search(atCap)).hasSize(QueryFilters.MAX_SEARCH_LENGTH);
    }

    private Status status(String code) {
        Status status = new Status();
        status.setId(UUID.randomUUID());
        status.setCode(code);
        return status;
    }

    private static org.primarion.module.communication.errors.core.ErrorCode errorOf(Throwable thrown) {
        assertThat(thrown).isInstanceOf(AppException.class);
        return ((AppException) thrown).getError();
    }
}
