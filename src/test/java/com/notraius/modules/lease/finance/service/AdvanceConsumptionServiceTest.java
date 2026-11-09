package com.notraius.modules.lease.finance.service;

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
import com.notraius.database.entities.reference.Status;
import com.notraius.database.entities.reference.Type;
import com.notraius.database.repositories.lease.LeaseBreakdownRepository;
import com.notraius.database.repositories.lease.LeaseDepositRepository;
import com.notraius.database.repositories.lease.LeaseDepositSplitRepository;
import com.notraius.modules.core.common.constants.NotraiusStatuses;
import com.notraius.modules.lease.state.service.LeaseStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.primarion.module.access.audit.AuditLogWriter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Consumption's laws (PLAN 6.3/6.4): the oldest HELD advance escrow is released
 * whole, the counter follows the hull, a refusal fails closed, and a dispute
 * freezes the machine.
 */
@ExtendWith(MockitoExtension.class)
class AdvanceConsumptionServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private LeaseBreakdownRepository breakdownRepository;
    @Mock private LeaseDepositRepository depositRepository;
    @Mock private LeaseDepositSplitRepository splitRepository;
    @Mock private PayPaymentClient payPaymentClient;
    @Mock private StatusResolver statusResolver;
    @Mock private LeaseStateService stateService;
    @Mock private AuditLogWriter auditLogWriter;

    private LeaseFinanceService service;

    @BeforeEach
    void setUp() {
        service = LeaseFinanceServiceFixture.aService()
                .breakdownRepository(breakdownRepository)
                .depositRepository(depositRepository)
                .splitRepository(splitRepository)
                .payPaymentClient(payPaymentClient)
                .statusResolver(statusResolver)
                .stateService(stateService)
                .auditLogWriter(auditLogWriter)
                .build();
        lenient().when(statusResolver.resolve(any(), any()))
                .thenAnswer(invocation -> status(invocation.getArgument(1)));
        lenient().when(breakdownRepository.save(any(LeaseBreakdown.class)))
                .thenAnswer(i -> i.getArgument(0));
        lenient().when(depositRepository.save(any(LeaseDeposit.class)))
                .thenAnswer(i -> i.getArgument(0));
        lenient().when(splitRepository.save(any(LeaseDepositSplit.class)))
                .thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("a UNITS advance releases its oldest HELD escrow whole and drops by one")
    void unitsReleasesOldest() {
        LeaseBreakdown bill = bill(NotraiusStatuses.LEASE_BREAKDOWN_OVERDUE);
        LeaseDeposit advance = advance(bill.getLease(), new BigDecimal("2"), PolicyUnit.UNITS);
        LeaseDepositSplit first = split(bill.getLease(), advance, 1);
        LeaseDepositSplit second = split(bill.getLease(), advance, 2);
        when(breakdownRepository.lockById(bill.getId())).thenReturn(Optional.of(bill));
        when(depositRepository.findByLeaseIdOrderByPolicyCodeAsc(bill.getLease().getId()))
                .thenReturn(List.of(advance));
        when(splitRepository.findByLeaseIdAndStatusCodeOrderBySequenceAsc(
                bill.getLease().getId(), NotraiusStatuses.LEASE_SPLIT_HELD))
                .thenReturn(List.of(first, second));

        assertThat(service.consume(bill.getId())).isTrue();

        assertThat(bill.getStatus().getCode()).isEqualTo(NotraiusStatuses.LEASE_BREAKDOWN_DEPOSITED);
        assertThat(bill.getSettledAt()).isNotNull();
        assertThat(bill.getEscrowSplitId()).isEqualTo(first.getSplitId());
        assertThat(first.getStatus().getCode()).isEqualTo(NotraiusStatuses.LEASE_SPLIT_RELEASED);
        assertThat(second.getStatus().getCode()).isEqualTo(NotraiusStatuses.LEASE_SPLIT_HELD);
        assertThat(advance.getBalance()).isEqualByComparingTo("1");
        verify(payPaymentClient).releaseSplit(first.getPaymentId(), first.getSplitId());
        verify(stateService).evaluateDeposit(bill.getLease());
    }

    @Test
    @DisplayName("an AMOUNT hull releases its single global split and its counter empties")
    void amountHullEmpties() {
        LeaseBreakdown bill = bill(NotraiusStatuses.LEASE_BREAKDOWN_OVERDUE);
        LeaseDeposit advance = advance(bill.getLease(), new BigDecimal("3"), PolicyUnit.AMOUNT);
        LeaseDepositSplit split = split(bill.getLease(), advance, 1);
        when(breakdownRepository.lockById(bill.getId())).thenReturn(Optional.of(bill));
        when(depositRepository.findByLeaseIdOrderByPolicyCodeAsc(bill.getLease().getId()))
                .thenReturn(List.of(advance));
        when(splitRepository.findByLeaseIdAndStatusCodeOrderBySequenceAsc(
                bill.getLease().getId(), NotraiusStatuses.LEASE_SPLIT_HELD))
                .thenReturn(List.of(split));

        assertThat(service.consume(bill.getId())).isTrue();

        assertThat(advance.getBalance()).isEqualByComparingTo("0");
        assertThat(bill.getStatus().getCode()).isEqualTo(NotraiusStatuses.LEASE_BREAKDOWN_DEPOSITED);
    }

    @Test
    @DisplayName("a second AMOUNT global split keeps its months when the first is consumed")
    void amountWithTwoHullsKeepsTheSecond() {
        LeaseBreakdown bill = bill(NotraiusStatuses.LEASE_BREAKDOWN_OVERDUE);
        LeaseDeposit advance = advance(bill.getLease(), new BigDecimal("6"), PolicyUnit.AMOUNT);
        // Two global splits, each covering three months: the counter holds six.
        advance.setStandardBalance(new BigDecimal("3"));
        LeaseDepositSplit first = split(bill.getLease(), advance, 1);
        LeaseDepositSplit second = split(bill.getLease(), advance, 2);
        when(breakdownRepository.lockById(bill.getId())).thenReturn(Optional.of(bill));
        when(depositRepository.findByLeaseIdOrderByPolicyCodeAsc(bill.getLease().getId()))
                .thenReturn(List.of(advance));
        when(splitRepository.findByLeaseIdAndStatusCodeOrderBySequenceAsc(
                bill.getLease().getId(), NotraiusStatuses.LEASE_SPLIT_HELD))
                .thenReturn(List.of(first, second));

        assertThat(service.consume(bill.getId())).isTrue();

        // The released split covered three months; the second split's three remain.
        assertThat(advance.getBalance()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("a refused release leaves the custody HELD and the row OVERDUE")
    void refusedReleaseFailsClosed() {
        LeaseBreakdown bill = bill(NotraiusStatuses.LEASE_BREAKDOWN_PENDING);
        LeaseDeposit advance = advance(bill.getLease(), new BigDecimal("2"), PolicyUnit.UNITS);
        LeaseDepositSplit split = split(bill.getLease(), advance, 1);
        when(breakdownRepository.lockById(bill.getId())).thenReturn(Optional.of(bill));
        when(depositRepository.findByLeaseIdOrderByPolicyCodeAsc(bill.getLease().getId()))
                .thenReturn(List.of(advance));
        when(splitRepository.findByLeaseIdAndStatusCodeOrderBySequenceAsc(
                bill.getLease().getId(), NotraiusStatuses.LEASE_SPLIT_HELD))
                .thenReturn(List.of(split));
        org.mockito.Mockito.doThrow(new RuntimeException("wallet closed"))
                .when(payPaymentClient).releaseSplit(any(), any());

        assertThat(service.consume(bill.getId())).isFalse();

        assertThat(bill.getStatus().getCode()).isEqualTo(NotraiusStatuses.LEASE_BREAKDOWN_OVERDUE);
        assertThat(bill.getSettledAt()).isNull();
        assertThat(split.getStatus().getCode()).isEqualTo(NotraiusStatuses.LEASE_SPLIT_HELD);
        assertThat(advance.getBalance()).isEqualByComparingTo("2");
        verify(stateService, never()).evaluateDeposit(any());
    }

    @Test
    @DisplayName("a dispute freezes forced actions: nothing is consumed")
    void disputedLeaseFreezes() {
        LeaseBreakdown bill = bill(NotraiusStatuses.LEASE_BREAKDOWN_OVERDUE);
        bill.getLease().setStatus(status(NotraiusStatuses.LEASE_DISPUTED));
        when(breakdownRepository.lockById(bill.getId())).thenReturn(Optional.of(bill));

        assertThat(service.consume(bill.getId())).isFalse();
        verify(payPaymentClient, never()).releaseSplit(any(), any());
    }

    @Test
    @DisplayName("a bill already settled by the tenant is never rewritten")
    void settledBillIsLeftAlone() {
        LeaseBreakdown bill = bill(NotraiusStatuses.LEASE_BREAKDOWN_SETTLED);
        when(breakdownRepository.lockById(bill.getId())).thenReturn(Optional.of(bill));

        assertThat(service.consume(bill.getId())).isFalse();
        verify(payPaymentClient, never()).releaseSplit(any(), any());
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private LeaseBreakdown bill(String statusCode) {
        Property property = new Property();
        property.setId(UUID.randomUUID());
        property.setOwnerId(OWNER);
        property.setStatus(status(NotraiusStatuses.PROPERTY_LEASED));

        Pricing pricing = new Pricing();
        pricing.setId(UUID.randomUUID());
        pricing.setProperty(property);
        pricing.setAmount(new BigDecimal("50.00"));

        Lease lease = new Lease();
        lease.setId(UUID.randomUUID());
        lease.setUserId(TENANT);
        lease.setOwnerId(OWNER);
        lease.setPricing(pricing);
        lease.setCheckable(pricing.isCheckable());
        lease.setDuration(pricing.getDuration());
        lease.setStatus(status(NotraiusStatuses.LEASE_ACTIVE));
        lease.setVersion(1);

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
        bill.setPeriodIndex(1);
        bill.setVersion(1);
        return bill;
    }

    private LeaseDeposit advance(Lease lease, BigDecimal balance, PolicyUnit unit) {
        Policy policy = new Policy();
        policy.setId(UUID.randomUUID());
        policy.setCode("ADVANCE");
        policy.setUnit(unit);

        Type type = new Type();
        type.setId(UUID.randomUUID());
        type.setCode(NotraiusStatuses.LEASE_DEPOSIT_ADVANCE);

        LeaseDeposit deposit = new LeaseDeposit();
        deposit.setId(UUID.randomUUID());
        deposit.setLease(lease);
        deposit.setPolicy(policy);
        deposit.setType(type);
        deposit.setBalance(balance);
        deposit.setStandardBalance(balance);
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
}
