package com.notraius.modules.lease.agreement.service;

import com.notraius.common.NotraiusErrors;
import com.notraius.common.integration.PayPaymentClient;
import com.notraius.common.services.StatusResolver;
import com.notraius.database.entities.asset.Pricing;
import com.notraius.database.entities.asset.Property;
import com.notraius.database.entities.config.Policy;
import com.notraius.database.entities.lease.Lease;
import com.notraius.database.entities.lease.LeaseDeposit;
import com.notraius.database.entities.lease.LeaseDepositSplit;
import com.notraius.database.entities.reference.Status;
import com.notraius.database.entities.reference.Type;
import com.notraius.database.entities.reference.Validity;
import com.notraius.database.repositories.asset.PropertyRepository;
import com.notraius.database.repositories.lease.LeaseDepositRepository;
import com.notraius.database.repositories.lease.LeaseDepositSplitRepository;
import com.notraius.database.repositories.lease.LeaseRepository;
import com.notraius.modules.core.common.constants.NotraiusStatuses;
import com.notraius.modules.lease.agreement.dto.response.LeaseResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.primarion.module.access.audit.AuditLogWriter;
import org.primarion.module.errors.AppException;
import org.primarion.module.errors.ConflictException;
import org.primarion.module.errors.ErrorCode;
import org.primarion.module.errors.ForbiddenException;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The pre-commitment exit (PLAN 6.0/6.1): the whole matrix returns split by
 * split, the property comes back the same instant, a committed lease cannot use
 * this door — and an unanswered funding refuses the cancel instead of refunding
 * custody that has not arrived.
 */
@ExtendWith(MockitoExtension.class)
class LeaseCancellationServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID STRANGER = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock private LeaseRepository leaseRepository;
    @Mock private LeaseDepositRepository depositRepository;
    @Mock private LeaseDepositSplitRepository splitRepository;
    @Mock private PropertyRepository propertyRepository;
    @Mock private PayPaymentClient payPaymentClient;
    @Mock private StatusResolver statusResolver;
    @Mock private AuditLogWriter auditLogWriter;

    private LeaseAgreementService service;

    @BeforeEach
    void setUp() {
        service = LeaseAgreementServiceFixture.aService()
                .leaseRepository(leaseRepository)
                .depositRepository(depositRepository)
                .splitRepository(splitRepository)
                .propertyRepository(propertyRepository)
                .payPaymentClient(payPaymentClient)
                .statusResolver(statusResolver)
                .auditLogWriter(auditLogWriter)
                .build();
        lenient().when(statusResolver.resolve(any(), any()))
                .thenAnswer(invocation -> status(invocation.getArgument(1)));
        lenient().when(leaseRepository.save(any(Lease.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(depositRepository.save(any(LeaseDeposit.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(splitRepository.save(any(LeaseDepositSplit.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(propertyRepository.save(any(Property.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("the cancel refunds every held split whole and puts the property back on the market")
    void cancelRefundsMatrixAndReleasesProperty() {
        Lease lease = lease(NotraiusStatuses.LEASE_PENDING, NotraiusStatuses.PROPERTY_LEASED);
        LeaseDeposit deposit = deposit(lease, new BigDecimal("2"));
        List<LeaseDepositSplit> splits = new ArrayList<>(List.of(
                split(lease, deposit, 1), split(lease, deposit, 2)));
        when(leaseRepository.findById(lease.getId())).thenReturn(Optional.of(lease));
        when(splitRepository.findByLeaseIdOrderBySequenceAsc(lease.getId())).thenReturn(splits);
        when(depositRepository.findByLeaseIdOrderByPolicyCodeAsc(lease.getId())).thenReturn(List.of(deposit));

        LeaseResponse response = service.cancel(lease.getId(), TENANT, "plans changed");

        assertThat(response.getStatus()).isEqualTo(NotraiusStatuses.LEASE_CANCELLED);
        assertThat(splits).allSatisfy(split ->
                assertThat(split.getStatus().getCode()).isEqualTo(NotraiusStatuses.LEASE_SPLIT_REFUNDED));
        assertThat(deposit.getBalance()).isEqualByComparingTo("0");
        assertThat(deposit.getStatus().getCode()).isEqualTo(NotraiusStatuses.LEASE_DEPOSIT_RETURNED);

        verify(payPaymentClient).refundSplit(splits.get(0).getPaymentId(), splits.get(0).getSplitId());
        verify(payPaymentClient).refundSplit(splits.get(1).getPaymentId(), splits.get(1).getSplitId());

        ArgumentCaptor<Property> saved = ArgumentCaptor.forClass(Property.class);
        verify(propertyRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus().getCode()).isEqualTo(NotraiusStatuses.PROPERTY_PUBLISHED);
    }

    @Test
    @DisplayName("the owner may cancel the funded intent too")
    void ownerMayCancel() {
        Lease lease = lease(NotraiusStatuses.LEASE_PENDING, NotraiusStatuses.PROPERTY_LEASED);
        LeaseDeposit deposit = deposit(lease, new BigDecimal("2"));
        when(leaseRepository.findById(lease.getId())).thenReturn(Optional.of(lease));
        when(splitRepository.findByLeaseIdOrderBySequenceAsc(lease.getId()))
                .thenReturn(List.of(split(lease, deposit, 1)));
        when(depositRepository.findByLeaseIdOrderByPolicyCodeAsc(lease.getId())).thenReturn(List.of(deposit));

        assertThat(service.cancel(lease.getId(), OWNER, null).getStatus())
                .isEqualTo(NotraiusStatuses.LEASE_CANCELLED);
    }

    @Test
    @DisplayName("a committed lease has no whole matrix left to return")
    void committedLeaseCannotBeCancelled() {
        Lease lease = lease(NotraiusStatuses.LEASE_ACTIVE, NotraiusStatuses.PROPERTY_LEASED);
        when(leaseRepository.findById(lease.getId())).thenReturn(Optional.of(lease));

        assertThatThrownBy(() -> service.cancel(lease.getId(), TENANT, null))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.LEASE_CANCEL_WINDOW_CLOSED));
        verify(payPaymentClient, never()).refundSplit(any(), any());
    }

    @Test
    @DisplayName("a stranger is neither party and cannot cancel")
    void strangerCannotCancel() {
        Lease lease = lease(NotraiusStatuses.LEASE_PENDING, NotraiusStatuses.PROPERTY_LEASED);
        when(leaseRepository.findById(lease.getId())).thenReturn(Optional.of(lease));

        assertThatThrownBy(() -> service.cancel(lease.getId(), STRANGER, null))
                .isInstanceOf(ForbiddenException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.LEASE_CANCEL_FORBIDDEN));
    }

    @Test
    @DisplayName("an unresolved funding refuses the cancel — custody may be arriving")
    void unresolvedFundingRefusesCancel() {
        Lease lease = lease(NotraiusStatuses.LEASE_PENDING, NotraiusStatuses.PROPERTY_LEASED);
        when(leaseRepository.findById(lease.getId())).thenReturn(Optional.of(lease));
        when(splitRepository.findByLeaseIdOrderBySequenceAsc(lease.getId())).thenReturn(List.of());
        when(payPaymentClient.findByReference(lease.getId()))
                .thenThrow(new RuntimeException("connection reset"));

        assertThatThrownBy(() -> service.cancel(lease.getId(), TENANT, null))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.LEASE_FUNDING_UNRESOLVED));
        assertThat(lease.getStatus().getCode()).isEqualTo(NotraiusStatuses.LEASE_PENDING);
    }

    @Test
    @DisplayName("pay saying 'no such reference' makes the cancel clean — nothing ever moved")
    void unfundedReferenceCancelsCleanly() {
        Lease lease = lease(NotraiusStatuses.LEASE_PENDING, NotraiusStatuses.PROPERTY_LEASED);
        when(leaseRepository.findById(lease.getId())).thenReturn(Optional.of(lease));
        when(splitRepository.findByLeaseIdOrderBySequenceAsc(lease.getId())).thenReturn(List.of());
        when(payPaymentClient.findByReference(lease.getId())).thenThrow(
                HttpClientErrorException.create(org.springframework.http.HttpStatus.NOT_FOUND, "not found",
                        org.springframework.http.HttpHeaders.EMPTY, null, null));
        when(depositRepository.findByLeaseIdOrderByPolicyCodeAsc(lease.getId())).thenReturn(List.of());

        LeaseResponse response = service.cancel(lease.getId(), TENANT, null);

        assertThat(response.getStatus()).isEqualTo(NotraiusStatuses.LEASE_CANCELLED);
        verify(payPaymentClient, never()).refundSplit(any(), any());
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private Lease lease(String leaseStatus, String propertyStatus) {
        Property property = new Property();
        property.setId(UUID.randomUUID());
        property.setTitle("Villa");
        property.setOwnerId(OWNER);
        property.setCurrencyId(UUID.randomUUID());
        property.setStatus(status(propertyStatus));
        property.setVersion(1);

        Pricing pricing = new Pricing();
        pricing.setId(UUID.randomUUID());
        pricing.setProperty(property);
        pricing.setAmount(new BigDecimal("50.00"));

        Validity validity = new Validity();
        validity.setId(UUID.randomUUID());
        validity.setFromDate(Instant.now());

        Lease lease = new Lease();
        lease.setId(UUID.randomUUID());
        lease.setUserId(TENANT);
        lease.setOwnerId(OWNER);
        lease.setPricing(pricing);
        lease.setCheckable(pricing.isCheckable());
        lease.setDuration(pricing.getDuration());
        lease.setValidity(validity);
        lease.setStatus(status(leaseStatus));
        lease.setVersion(1);
        return lease;
    }

    private LeaseDeposit deposit(Lease lease, BigDecimal balance) {
        Policy policy = new Policy();
        policy.setId(UUID.randomUUID());
        policy.setCode("ADVANCE");

        Type type = new Type();
        type.setId(UUID.randomUUID());
        type.setCode("ADVANCE");

        LeaseDeposit deposit = new LeaseDeposit();
        deposit.setId(UUID.randomUUID());
        deposit.setLease(lease);
        deposit.setPolicy(policy);
        deposit.setType(type);
        deposit.setBalance(balance);
        deposit.setStatus(status(NotraiusStatuses.LEASE_DEPOSIT_HELD));
        deposit.setVersion(1);
        return deposit;
    }

    private LeaseDepositSplit split(Lease lease, LeaseDeposit deposit, int sequence) {
        LeaseDepositSplit split = new LeaseDepositSplit();
        split.setId(UUID.randomUUID());
        split.setLease(lease);
        split.setDeposit(deposit);
        split.setPaymentId(UUID.randomUUID());
        split.setSplitId(UUID.randomUUID());
        split.setReferenceId(UUID.randomUUID());
        split.setAmount(new BigDecimal("50.00"));
        split.setSequence(sequence);
        split.setStatus(status(NotraiusStatuses.LEASE_SPLIT_HELD));
        return split;
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
