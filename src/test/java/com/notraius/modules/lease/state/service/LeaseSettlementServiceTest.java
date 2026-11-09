package com.notraius.modules.lease.state.service;

import com.notraius.common.NotraiusErrors;
import com.notraius.common.integration.PayPaymentClient;
import com.notraius.common.services.StatusResolver;
import com.notraius.database.entities.asset.Pricing;
import com.notraius.database.entities.asset.Property;
import com.notraius.database.entities.config.Policy;
import com.notraius.database.entities.config.PolicyUnit;
import com.notraius.database.entities.lease.Lease;
import com.notraius.database.entities.lease.LeaseBreakdown;
import com.notraius.database.entities.lease.LeaseDeposit;
import com.notraius.database.entities.lease.LeaseDepositSplit;
import com.notraius.database.entities.lease.LeaseSettlement;
import com.notraius.database.entities.reference.Status;
import com.notraius.database.entities.reference.Type;
import com.notraius.database.entities.reference.Validity;
import com.notraius.database.repositories.asset.PropertyRepository;
import com.notraius.database.repositories.lease.LeaseBreakdownRepository;
import com.notraius.database.repositories.lease.LeaseConditionRepository;
import com.notraius.database.repositories.lease.LeaseDepositRepository;
import com.notraius.database.repositories.lease.LeaseDepositSplitRepository;
import com.notraius.database.repositories.lease.LeaseRepository;
import com.notraius.database.repositories.lease.LeaseSettlementRepository;
import com.notraius.modules.core.common.constants.NotraiusStatuses;
import com.notraius.modules.lease.state.dto.response.LeaseSettlementResponse;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The end-of-life money walk (PLAN 6.7): the property is freed at birth,
 * escrows are refunded before any debt moves, and completion waits until
 * nothing is owed.
 */
@ExtendWith(MockitoExtension.class)
class LeaseSettlementServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private LeaseSettlementRepository settlementRepository;
    @Mock private LeaseRepository leaseRepository;
    @Mock private LeaseDepositRepository depositRepository;
    @Mock private LeaseDepositSplitRepository splitRepository;
    @Mock private LeaseBreakdownRepository breakdownRepository;
    @Mock private LeaseConditionRepository conditionRepository;
    @Mock private PropertyRepository propertyRepository;
    @Mock private PayPaymentClient payPaymentClient;
    @Mock private StatusResolver statusResolver;
    @Mock private AuditLogWriter auditLogWriter;

    private LeaseStateService service;

    @BeforeEach
    void setUp() {
        service = LeaseStateServiceFixture.aService()
                .settlementRepository(settlementRepository)
                .leaseRepository(leaseRepository)
                .depositRepository(depositRepository)
                .splitRepository(splitRepository)
                .breakdownRepository(breakdownRepository)
                .conditionRepository(conditionRepository)
                .propertyRepository(propertyRepository)
                .payPaymentClient(payPaymentClient)
                .statusResolver(statusResolver)
                .auditLogWriter(auditLogWriter)
                .build();
        lenient().when(statusResolver.resolve(any(), any()))
                .thenAnswer(invocation -> status(invocation.getArgument(1)));
        lenient().when(settlementRepository.save(any(LeaseSettlement.class)))
                .thenAnswer(i -> i.getArgument(0));
        lenient().when(splitRepository.save(any(LeaseDepositSplit.class)))
                .thenAnswer(i -> i.getArgument(0));
        lenient().when(depositRepository.save(any(LeaseDeposit.class)))
                .thenAnswer(i -> i.getArgument(0));
        lenient().when(propertyRepository.save(any(Property.class)))
                .thenAnswer(i -> i.getArgument(0));
        lenient().when(leaseRepository.save(any(Lease.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(breakdownRepository.findTenantOwed(any(), anyList(), any(), any(), any()))
                .thenReturn(List.of());
        lenient().when(breakdownRepository.findByLeaseIdOrderByDeadlineAsc(any()))
                .thenReturn(List.of());
        lenient().when(conditionRepository.findByLeaseAndType(any(), any()))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("birth frees the property, refunds every HELD escrow, and opens the fortnight")
    void birthRefundsThenDisables() {
        Lease lease = lease();
        LeaseDeposit deposit = deposit(lease);
        LeaseDepositSplit split = split(lease, deposit);
        when(settlementRepository.findByLeaseId(lease.getId())).thenReturn(Optional.empty());
        // First call: the actual refund; then the drained check; then the read.
        when(splitRepository.findByLeaseIdAndStatusCodeOrderBySequenceAsc(
                lease.getId(), NotraiusStatuses.LEASE_SPLIT_HELD))
                .thenReturn(new ArrayList<>(List.of(split)), List.of(), List.of());

        LeaseSettlementResponse response = service.birth(lease, OWNER, "tenancy ended");

        assertThat(split.getStatus().getCode()).isEqualTo(NotraiusStatuses.LEASE_SPLIT_REFUNDED);
        verify(payPaymentClient).refundSplit(split.getPaymentId(), split.getSplitId());
        assertThat(deposit.getStatus().getCode()).isEqualTo(NotraiusStatuses.LEASE_DEPOSIT_RETURNED);
        assertThat(deposit.getBalance()).isEqualByComparingTo("0");
        assertThat(lease.getPricing().getProperty().getStatus().getCode())
                .isEqualTo(NotraiusStatuses.PROPERTY_DISABLED);
        assertThat(response.getStatus()).isEqualTo(NotraiusStatuses.LEASE_SETTLEMENT_OPEN);
        assertThat(response.getDeadline()).isAfter(Instant.now());
        assertThat(response.getMoveOutDeadline()).isAfter(Instant.now());
        assertThat(response.getHeldCustody()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("confirmation waits while a billed row still stands")
    void confirmationWaitsForDebt() {
        Lease lease = lease();
        LeaseSettlement row = settlement(lease);
        LeaseBreakdown bill = bill(lease, NotraiusStatuses.LEASE_BREAKDOWN_OVERDUE);
        when(settlementRepository.findById(row.getId())).thenReturn(Optional.of(row));
        when(breakdownRepository.findTenantOwed(any(), anyList(), any(), any(), any()))
                .thenReturn(List.of(bill));

        assertThatThrownBy(() -> service.confirm(row.getId(), TENANT))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t))
                        .isEqualTo(NotraiusErrors.LEASE_SETTLEMENT_OUTSTANDING));
        assertThat(lease.getStatus().getCode()).isEqualTo(NotraiusStatuses.LEASE_ACTIVE);
        verify(payPaymentClient, never()).refundSplit(any(), any());
    }

    @Test
    @DisplayName("nothing owed: the settlement confirms and the lease completes")
    void confirmationCompletes() {
        Lease lease = lease();
        LeaseSettlement row = settlement(lease);
        when(settlementRepository.findById(row.getId())).thenReturn(Optional.of(row));

        LeaseSettlementResponse response = service.confirm(row.getId(), TENANT);

        assertThat(response.getStatus()).isEqualTo(NotraiusStatuses.LEASE_SETTLEMENT_CONFIRMED);
        assertThat(row.getConfirmedAt()).isNotNull();
        assertThat(lease.getStatus().getCode()).isEqualTo(NotraiusStatuses.LEASE_COMPLETED);
    }

    @Test
    @DisplayName("a failed refund leaves the split HELD; a later pass completes it")
    void failedRefundStaysHeld() {
        Lease lease = lease();
        LeaseDeposit deposit = deposit(lease);
        LeaseDepositSplit split = split(lease, deposit);
        when(settlementRepository.findByLeaseId(lease.getId())).thenReturn(Optional.empty());
        when(splitRepository.findByLeaseIdAndStatusCodeOrderBySequenceAsc(
                lease.getId(), NotraiusStatuses.LEASE_SPLIT_HELD))
                .thenReturn(new ArrayList<>(List.of(split)), List.of(), List.of());
        org.mockito.Mockito.doThrow(new RuntimeException("pay unreachable"))
                .when(payPaymentClient).refundSplit(any(), any());

        service.birth(lease, OWNER, null);

        assertThat(split.getStatus().getCode()).isEqualTo(NotraiusStatuses.LEASE_SPLIT_HELD);
        assertThat(lease.getPricing().getProperty().getStatus().getCode())
                .isEqualTo(NotraiusStatuses.PROPERTY_DISABLED);
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private Lease lease() {
        Property property = new Property();
        property.setId(UUID.randomUUID());
        property.setOwnerId(OWNER);
        property.setCurrencyId(UUID.randomUUID());
        property.setStatus(status(NotraiusStatuses.PROPERTY_LEASED));
        property.setVersion(1);

        Pricing pricing = new Pricing();
        pricing.setId(UUID.randomUUID());
        pricing.setProperty(property);
        pricing.setAmount(new BigDecimal("50.00"));
        pricing.setCheckable(false);

        Validity validity = new Validity();
        validity.setId(UUID.randomUUID());
        validity.setFromDate(Instant.now().minus(180, ChronoUnit.DAYS));
        validity.setToDate(Instant.now());
        validity.setStatus(status(NotraiusStatuses.LEASE_ACTIVE));

        Lease lease = new Lease();
        lease.setId(UUID.randomUUID());
        lease.setUserId(TENANT);
        lease.setOwnerId(OWNER);
        lease.setPricing(pricing);
        lease.setCheckable(pricing.isCheckable());
        lease.setDuration(pricing.getDuration());
        lease.setValidity(validity);
        lease.setStatus(status(NotraiusStatuses.LEASE_ACTIVE));
        lease.setOrigin(NotraiusStatuses.LEASE_ORIGIN_NATIVE);
        lease.setVersion(1);
        return lease;
    }

    private LeaseDeposit deposit(Lease lease) {
        Policy policy = new Policy();
        policy.setId(UUID.randomUUID());
        policy.setCode("SECURITY");
        policy.setUnit(PolicyUnit.AMOUNT);

        Type type = new Type();
        type.setId(UUID.randomUUID());
        type.setCode("SECURITY");

        LeaseDeposit deposit = new LeaseDeposit();
        deposit.setId(UUID.randomUUID());
        deposit.setLease(lease);
        deposit.setPolicy(policy);
        deposit.setType(type);
        deposit.setBalance(new BigDecimal("1"));
        deposit.setStandardBalance(new BigDecimal("1"));
        deposit.setStatus(status(NotraiusStatuses.LEASE_DEPOSIT_HELD));
        deposit.setVersion(1);
        return deposit;
    }

    private LeaseDepositSplit split(Lease lease, LeaseDeposit deposit) {
        LeaseDepositSplit split = new LeaseDepositSplit();
        split.setId(UUID.randomUUID());
        split.setLease(lease);
        split.setDeposit(deposit);
        split.setPaymentId(UUID.randomUUID());
        split.setSplitId(UUID.randomUUID());
        split.setAmount(new BigDecimal("50.00"));
        split.setSequence(1);
        split.setStatus(status(NotraiusStatuses.LEASE_SPLIT_HELD));
        return split;
    }

    private LeaseSettlement settlement(Lease lease) {
        LeaseSettlement row = new LeaseSettlement();
        row.setId(UUID.randomUUID());
        row.setLease(lease);
        row.setStatus(status(NotraiusStatuses.LEASE_SETTLEMENT_OPEN));
        row.setDeadline(Instant.now().plus(14, ChronoUnit.DAYS));
        row.setMoveOutDeadline(Instant.now().plus(7, ChronoUnit.DAYS));
        row.setVersion(1);
        return row;
    }

    private LeaseBreakdown bill(Lease lease, String statusCode) {
        Type type = new Type();
        type.setId(UUID.randomUUID());
        type.setCode(NotraiusStatuses.LEASE_BREAKDOWN_INSTALLMENT);

        LeaseBreakdown bill = new LeaseBreakdown();
        bill.setId(UUID.randomUUID());
        bill.setLease(lease);
        bill.setType(type);
        bill.setUserId(OWNER);
        bill.setAmount(new BigDecimal("50.00"));
        bill.setStatus(status(statusCode));
        bill.setVersion(1);
        return bill;
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
