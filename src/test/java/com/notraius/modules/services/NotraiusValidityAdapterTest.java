package com.notraius.modules.services;

import com.notraius.database.entities.lease.reference.status.Status;
import com.notraius.database.entities.lease.reference.amenity.Validity;
import com.notraius.database.repositories.lease.reference.communication.ValidityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.primarion.database.entities.lifecycle.ValidityView;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.notraius.common.services.logs.NotraiusValidityAdapter;
import com.notraius.common.services.logs.StatusResolver;

/**
 * The shared schedulers read a page of windows per tick through
 * {@link org.primarion.database.entities.lifecycle.ValidityPort#findMany}. The
 * port's default implementation loops one query per id; the host override must
 * be one query, and must answer exactly what the loop would have answered.
 */
@ExtendWith(MockitoExtension.class)
class NotraiusValidityAdapterTest {

    private static final Instant NOW = Instant.parse("2026-03-15T12:00:00Z");

    @Mock
    private ValidityRepository validityRepository;

    @Mock
    private StatusResolver statusResolver;

    private NotraiusValidityAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new NotraiusValidityAdapter(validityRepository, statusResolver);
    }

    @Test
    @DisplayName("a page of windows is ONE query, not one per row")
    void findManyIsOneQuery() {
        Validity first = validity("ACTIVE", NOW.minus(1, ChronoUnit.DAYS), null);
        Validity second = validity("PENDING", NOW, NOW.plus(1, ChronoUnit.DAYS));
        when(validityRepository.findAllById(List.of(first.getId(), second.getId())))
                .thenReturn(List.of(first, second));

        Map<UUID, ValidityView> windows = adapter.findMany(List.of(first.getId(), second.getId()));

        assertThat(windows).containsOnlyKeys(first.getId(), second.getId());
        assertThat(windows.get(first.getId()).statusCode()).isEqualTo("ACTIVE");
        assertThat(windows.get(second.getId()).statusCode()).isEqualTo("PENDING");
        assertThat(windows.get(second.getId()).isLive(NOW)).isTrue();

        verify(validityRepository, never()).findById(any());
    }

    @Test
    @DisplayName("a duplicate id is one id, and a null id is no id at all")
    void duplicateAndNullIdsCollapse() {
        Validity only = validity("ACTIVE", NOW.minus(1, ChronoUnit.DAYS), null);
        when(validityRepository.findAllById(List.of(only.getId()))).thenReturn(List.of(only));

        Map<UUID, ValidityView> windows = adapter.findMany(
                Arrays.asList(only.getId(), only.getId(), null));

        assertThat(windows).containsOnlyKeys(only.getId());
    }

    @Test
    @DisplayName("a row the host no longer has stays absent — the loop's answer, kept")
    void missingRowsStayAbsent() {
        Validity present = validity("ACTIVE", NOW.minus(1, ChronoUnit.DAYS), null);
        UUID gone = UUID.randomUUID();
        when(validityRepository.findAllById(List.of(present.getId(), gone)))
                .thenReturn(List.of(present));

        assertThat(adapter.findMany(List.of(present.getId(), gone)))
                .containsOnlyKeys(present.getId());
    }

    @Test
    @DisplayName("an empty page never reaches the database")
    void emptyPageNeverQueries() {
        assertThat(adapter.findMany(List.of())).isEmpty();
        assertThat(adapter.findMany(null)).isEmpty();
        assertThat(adapter.findMany(Arrays.asList((UUID) null))).isEmpty();
        verifyNoInteractions(validityRepository);
    }

    @Test
    @DisplayName("the single read answers what the page read answers")
    void findAgreesWithFindMany() {
        Validity row = validity("ACTIVE", NOW.minus(1, ChronoUnit.DAYS), NOW.plus(1, ChronoUnit.DAYS));
        when(validityRepository.findById(row.getId())).thenReturn(Optional.of(row));
        when(validityRepository.findAllById(List.of(row.getId()))).thenReturn(List.of(row));

        Optional<ValidityView> single = adapter.find(row.getId());
        ValidityView batched = adapter.findMany(List.of(row.getId())).get(row.getId());

        assertThat(single).contains(batched);
    }

    private Validity validity(String statusCode, Instant from, Instant to) {
        Status status = new Status();
        status.setId(UUID.randomUUID());
        status.setCode(statusCode);

        Validity validity = new Validity();
        validity.setId(UUID.randomUUID());
        validity.setStatus(status);
        validity.setFromDate(from);
        validity.setToDate(to);
        return validity;
    }
}
