package com.notraius.modules.modules.lease.service.lease.agreement;

import com.notraius.common.NotraiusErrors;
import com.notraius.database.entities.lease.lease.records.Lease;
import com.notraius.database.entities.lease.reference.status.Status;
import com.notraius.database.entities.lease.reference.amenity.Validity;
import com.notraius.database.repositories.lease.lease.lease.LeaseDepositRepository;
import com.notraius.database.repositories.lease.lease.lease.LeaseDepositSplitRepository;
import com.notraius.database.repositories.lease.lease.lease.LeaseRepository;
import com.notraius.modules.core.core.common.constants.NotraiusStatuses;
import com.notraius.modules.lease.lease.agreement.dto.response.LeaseResponse;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.notraius.modules.lease.lease.agreement.service.LeaseAgreementService;
import com.notraius.modules.lease.lease.agreement.service.LeaseNextActions;
import com.notraius.modules.modules.lease.service.lease.agreement.LeaseAgreementServiceFixture;

/**
 * The two tenancy pages as a party walks them, and the acts the row hands back.
 * <p>
 * Same laws as the visit pages — no filter means every status, a named status
 * must exist, an inverted window is refused, the order is a whitelist — plus the
 * one that made the PENDING lease painful: the tenant's next act is not the
 * owner's, and an import waits on its own verification.
 */
@ExtendWith(MockitoExtension.class)
class LeaseListingServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant FOREVER = Instant.parse("9999-12-31T23:59:59Z");

    @Mock
    private LeaseRepository leaseRepository;

    @Mock
    private LeaseDepositRepository depositRepository;

    @Mock
    private LeaseDepositSplitRepository splitRepository;

    @BeforeEach
    void noDeposits() {
        org.mockito.Mockito.lenient()
                .when(depositRepository.findByLeaseIdOrderByPolicyCodeAsc(any())).thenReturn(List.of());
        org.mockito.Mockito.lenient()
                .when(splitRepository.findByLeaseIdOrderBySequenceAsc(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("no status filter means every lease status, so SQL keeps one indexed IN")
    void unfilteredPageAsksForEveryStatus() {
        LeaseAgreementService service = service();
        when(leaseRepository.searchAsTenant(eq(TENANT), any(), any(), any(), any())).thenReturn(Page.empty());

        service.listMine(TENANT, null, null, null, PageRequest.of(0, 20));

        ArgumentCaptor<List<String>> statuses = statusCaptor();
        verify(leaseRepository).searchAsTenant(eq(TENANT), statuses.capture(),
                eq(Instant.EPOCH), eq(FOREVER), any());
        assertThat(statuses.getValue()).containsExactly(
                NotraiusStatuses.LEASE_PENDING,
                NotraiusStatuses.LEASE_CONFIRMED,
                NotraiusStatuses.LEASE_ACTIVE,
                NotraiusStatuses.LEASE_DISPUTED,
                NotraiusStatuses.LEASE_COMPLETED,
                NotraiusStatuses.LEASE_CANCELLED,
                NotraiusStatuses.LEASE_EXPIRED);
    }

    @Test
    @DisplayName("a status filter is case-tolerant and de-duplicated; an unknown one is refused by name")
    void statusFilterIsNormalisedAndChecked() {
        LeaseAgreementService service = service();
        when(leaseRepository.searchAsOwner(eq(OWNER), any(), any(), any(), any())).thenReturn(Page.empty());

        Instant from = Instant.now().minus(1, ChronoUnit.DAYS);
        service.listOwned(OWNER, List.of(" active ", "ACTIVE"), from, null, PageRequest.of(0, 20));

        ArgumentCaptor<List<String>> statuses = statusCaptor();
        verify(leaseRepository).searchAsOwner(eq(OWNER), statuses.capture(), eq(from), eq(FOREVER), any());
        assertThat(statuses.getValue()).containsExactly(NotraiusStatuses.LEASE_ACTIVE);

        assertThatThrownBy(() -> service.listMine(TENANT, List.of("ON_HOLD"), null, null,
                PageRequest.of(0, 20)))
                .isInstanceOf(BadRequestException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.LEASE_FILTER_INVALID));
    }

    @Test
    @DisplayName("an inverted window is a refusal, not an empty page")
    void invertedWindowIsRefused() {
        LeaseAgreementService service = service();
        Instant now = Instant.now();

        assertThatThrownBy(() -> service.listMine(TENANT, null, now, now.minus(1, ChronoUnit.HOURS),
                PageRequest.of(0, 20)))
                .isInstanceOf(BadRequestException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.LEASE_FILTER_INVALID));
        verify(leaseRepository, org.mockito.Mockito.never())
                .searchAsTenant(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("the order is a whitelist: recent stays the default, window is soonest term first")
    void sortIsAWhitelist() {
        Sort recent = LeaseAgreementService.sortOf(null);
        assertThat(recent.getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(LeaseAgreementService.sortOf("recent")).isEqualTo(recent);

        Sort window = LeaseAgreementService.sortOf("WINDOW");
        assertThat(window.getOrderFor("validity.fromDate").getDirection()).isEqualTo(Sort.Direction.ASC);

        assertThatThrownBy(() -> LeaseAgreementService.sortOf("cheapest"))
                .isInstanceOf(BadRequestException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.LEASE_FILTER_INVALID));
    }

    @Test
    @DisplayName("each party's page hands back that party's own next act")
    void pagesCarryTheCallersNextActs() {
        LeaseAgreementService service = service();
        Lease pending = lease(NotraiusStatuses.LEASE_PENDING, NotraiusStatuses.LEASE_ORIGIN_NATIVE);
        when(leaseRepository.searchAsOwner(eq(OWNER), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(pending)));
        when(leaseRepository.searchAsTenant(eq(TENANT), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(pending)));
        // No splits: the funding has not landed, so nobody confirms yet.
        LeaseResponse ownerRow = service.listOwned(OWNER, null, null, null, PageRequest.of(0, 20))
                .getContent().getFirst();
        assertThat(ownerRow.getNextActions()).containsExactly(LeaseNextActions.CANCEL);

        LeaseResponse tenantRow = service.listMine(TENANT, null, null, null, PageRequest.of(0, 20))
                .getContent().getFirst();
        assertThat(tenantRow.getNextActions()).containsExactly(LeaseNextActions.CANCEL);
    }

    @Test
    @DisplayName("an imported lease PENDING on its tenant offers verification, not the owner's confirm")
    void importedLeaseOffersVerificationToItsTenant() {
        LeaseAgreementService service = service();
        Lease imported = lease(NotraiusStatuses.LEASE_PENDING, NotraiusStatuses.LEASE_ORIGIN_IMPORT);
        when(leaseRepository.findById(imported.getId())).thenReturn(Optional.of(imported));

        LeaseResponse tenantView = service.getLease(imported.getId(), TENANT);
        // The custody never answered, so accepting would verify a holding nobody
        // can find; refusing is always the tenant's.
        assertThat(tenantView.getNextActions()).containsExactly(LeaseNextActions.REFUSE_IMPORT);
        assertThat(service.getLease(imported.getId(), OWNER).getNextActions())
                .containsExactly(LeaseNextActions.CANCEL);
    }

    @Test
    @DisplayName("a confirmed checkable tenancy is the tenant's move-in report")
    void confirmedLeaseAsksTheTenantForThePremises() {
        LeaseAgreementService service = service();
        Lease confirmed = lease(NotraiusStatuses.LEASE_CONFIRMED, NotraiusStatuses.LEASE_ORIGIN_NATIVE);
        when(leaseRepository.findById(confirmed.getId())).thenReturn(Optional.of(confirmed));

        assertThat(service.getLease(confirmed.getId(), TENANT).getNextActions())
                .containsExactly(LeaseNextActions.REPORT_CONDITION);
        assertThat(service.getLease(confirmed.getId(), OWNER).getNextActions()).isEmpty();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private LeaseAgreementService service() {
        return LeaseAgreementServiceFixture.aService()
                .leaseRepository(leaseRepository)
                .depositRepository(depositRepository)
                .splitRepository(splitRepository)
                .build();
    }

    private Lease lease(String status, String origin) {
        Lease lease = new Lease();
        lease.setId(UUID.randomUUID());
        lease.setUserId(TENANT);
        lease.setOwnerId(OWNER);
        lease.setOrigin(origin);
        lease.setVersion(1);
        lease.setStatus(status(status));
        Validity validity = new Validity();
        validity.setId(UUID.randomUUID());
        validity.setFromDate(Instant.now().minus(1, ChronoUnit.DAYS));
        validity.setToDate(Instant.now().plus(30, ChronoUnit.DAYS));
        lease.setValidity(validity);
        return lease;
    }

    private Status status(String code) {
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
