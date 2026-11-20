package com.notraius.modules.modules.lease.service.lease.timeline;

import com.notraius.common.NotraiusErrors;
import com.notraius.config.notraius.properties.NotraiusProperties;
import com.notraius.database.repositories.lease.lease.lease.LeaseRepository;
import com.notraius.modules.core.core.common.constants.NotraiusStatuses;
import com.notraius.modules.core.core.common.schedule.sweep.SweepKey;
import com.notraius.modules.core.core.common.schedule.sweep.SweepRow;
import com.notraius.modules.lease.lease.agreement.dto.response.LeaseResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.primarion.module.communication.errors.client.ConflictException;

import com.notraius.database.entities.lease.reference.status.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.notraius.modules.lease.lease.agreement.service.LeaseAgreementService;
import com.notraius.modules.modules.lease.service.lease.agreement.LeaseAgreementServiceFixture;
import java.time.temporal.ChronoUnit;

/**
 * PLAN 6.1's birth clock: a PENDING lease nobody confirmed inside the window
 * expires, and the sweep survives a row it cannot settle.
 */
@ExtendWith(MockitoExtension.class)
class LeaseExpiryServiceTest {

    private static final UUID FIRST = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SECOND = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private LeaseRepository leaseRepository;

    private NotraiusProperties properties;
    private LeaseAgreementService service;

    @BeforeEach
    void setUp() {
        properties = new NotraiusProperties();
        service = spy(LeaseAgreementServiceFixture.aService()
                .leaseRepository(leaseRepository).properties(properties).build());
        // The sweep's own law is under test; the cancel walk has its own test.
        lenient().doAnswer(invocation -> new LeaseResponse()).when(service).expire(any());
    }

    @Test
    @DisplayName("every stale lease is expired through the cancel walk, in its own transaction")
    void expiresEveryStaleLease() {
        stalePage(FIRST, SECOND);

        assertThat(service.sweep()).isEqualTo(2);

        verify(service).expire(FIRST);
        verify(service).expire(SECOND);
    }

    @Test
    @DisplayName("the window and the lookback are the configuration's, never literals")
    void boundsComeFromConfiguration() {
        properties.setLeaseStalenessWindowHours(6);
        properties.setLeaseSweepLookbackHours(48);
        stalePage();

        Instant before = Instant.now();
        service.sweep();

        ArgumentCaptor<Instant> fence = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> lookback = ArgumentCaptor.forClass(Instant.class);
        verify(leaseRepository).findStalePage(eq(NotraiusStatuses.LEASE_PENDING),
                fence.capture(), lookback.capture(), any(), any(), any());
        assertThat(fence.getValue()).isBetween(before.minus(java.time.Duration.ofHours(6)).minusSeconds(5),
                before.minus(java.time.Duration.ofHours(6)).plusSeconds(5));
        assertThat(lookback.getValue()).isBetween(before.minus(java.time.Duration.ofHours(48)).minusSeconds(5),
                before.minus(java.time.Duration.ofHours(48)).plusSeconds(5));
    }

    @Test
    @DisplayName("one row whose funding cannot be answered does not stop the sweep")
    void oneFailedRowDoesNotStopTheSweep() {
        stalePage(FIRST, SECOND);
        doThrow(new ConflictException(NotraiusErrors.LEASE_FUNDING_UNRESOLVED, "pay is silent"))
                .when(service).expire(FIRST);

        assertThat(service.sweep()).isEqualTo(1);

        verify(service).expire(FIRST);
        verify(service).expire(SECOND);
    }

    @Test
    @DisplayName("an empty worklist settles nothing and touches no lease")
    void emptyWorklist() {
        stalePage();

        assertThat(service.sweep()).isZero();

        verify(service, never()).expire(any());
    }

    /** One keyset page of the staleness worklist; a short page ends the pass. */
    private void stalePage(UUID... ids) {
        List<SweepKey> page = java.util.Arrays.stream(ids)
                .map(id -> (SweepKey) new SweepRow(id, Instant.now().minus(2, java.time.temporal.ChronoUnit.HOURS)))
                .toList();
        when(leaseRepository.findStalePage(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(page);
    }
}
