package com.notraius.modules.modules.lease.service.finance;

import com.notraius.common.NotraiusErrors;
import com.notraius.common.integration.pay.PayPaymentClient;
import com.notraius.common.services.logs.StatusResolver;
import com.notraius.database.entities.asset.asset.Pricing;
import com.notraius.database.entities.asset.asset.Property;
import com.notraius.database.entities.lease.lease.records.Lease;
import com.notraius.database.entities.lease.lease.records.LeaseBreakdown;
import com.notraius.database.entities.lease.reference.status.Status;
import com.notraius.database.entities.lease.reference.status.Type;
import com.notraius.database.repositories.lease.lease.records.LeaseBreakdownRepository;
import com.notraius.database.repositories.lease.lease.lease.LeaseRepository;
import com.notraius.modules.core.core.common.constants.NotraiusStatuses;
import com.notraius.modules.lease.lease.finance.dto.request.LeaseCashConfirmRequest;
import com.notraius.modules.lease.lease.finance.dto.response.LeaseCashConfirmResponse;
import com.notraius.modules.lease.lease.finance.dto.response.LeaseInstallmentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.primarion.module.app.access.consent.AuditLogWriter;
import org.primarion.module.communication.errors.exception.AppException;
import org.primarion.module.communication.errors.client.BadRequestException;
import org.primarion.module.communication.errors.client.ConflictException;
import org.primarion.module.communication.errors.core.ErrorCode;
import org.primarion.module.communication.errors.client.ForbiddenException;

import java.math.BigDecimal;
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
import com.notraius.modules.lease.lease.finance.LeaseFinanceService;
import com.notraius.modules.modules.lease.service.lease.agreement.LeaseFinanceServiceFixture;

/**
 * The two payment doors (PLAN 6.3/6.8): the wallet path is direct and exact, the
 * cash path records what physically arrived and never part-settles a bill.
 */
@ExtendWith(MockitoExtension.class)
class InstallmentPaymentServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private LeaseBreakdownRepository breakdownRepository;
    @Mock private LeaseRepository leaseRepository;
    @Mock private PayPaymentClient payPaymentClient;
    @Mock private StatusResolver statusResolver;
    @Mock private AuditLogWriter auditLogWriter;

    private LeaseFinanceService service;

    @BeforeEach
    void setUp() {
        service = LeaseFinanceServiceFixture.aService()
                .breakdownRepository(breakdownRepository)
                .leaseRepository(leaseRepository)
                .payPaymentClient(payPaymentClient)
                .statusResolver(statusResolver)
                .auditLogWriter(auditLogWriter)
                .build();
        lenient().when(statusResolver.resolve(any(), any()))
                .thenAnswer(invocation -> status(invocation.getArgument(1)));
        lenient().when(breakdownRepository.save(any(LeaseBreakdown.class)))
                .thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("the tenant pays the exact figure directly to the row's recipient")
    void payIsDirectAndExact() {
        LeaseBreakdown bill = bill(NotraiusStatuses.LEASE_BREAKDOWN_PENDING);
        when(breakdownRepository.lockById(bill.getId())).thenReturn(Optional.of(bill));
        when(payPaymentClient.createDirectPayment(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PayPaymentClient.PaymentRef(UUID.randomUUID(), new BigDecimal("50.00"),
                        "EGP", "SUCCEEDED", null, List.of()));

        LeaseInstallmentResponse response = service.pay(bill.getId(), TENANT);

        assertThat(response.getStatus()).isEqualTo(NotraiusStatuses.LEASE_BREAKDOWN_SETTLED);
        assertThat(bill.getSettledAt()).isNotNull();
        ArgumentCaptor<UUID> payee = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(payPaymentClient).createDirectPayment(any(), any(), amount.capture(), any(), any(),
                payee.capture(), any());
        assertThat(payee.getValue()).isEqualTo(OWNER);
        assertThat(amount.getValue()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("only the tenant may pay an installment")
    void onlyTenantPays() {
        LeaseBreakdown bill = bill(NotraiusStatuses.LEASE_BREAKDOWN_PENDING);
        when(breakdownRepository.lockById(bill.getId())).thenReturn(Optional.of(bill));

        assertThatThrownBy(() -> service.pay(bill.getId(), OWNER))
                .isInstanceOf(ForbiddenException.class)
                .satisfies(t -> assertThat(errorOf(t))
                        .isEqualTo(NotraiusErrors.LEASE_BREAKDOWN_PAYER_FORBIDDEN));
        verify(payPaymentClient, never()).createDirectPayment(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a pay answer above the bill refuses and leaves the row unpaid")
    void wrongAnsweredAmountRefuses() {
        LeaseBreakdown bill = bill(NotraiusStatuses.LEASE_BREAKDOWN_PENDING);
        when(breakdownRepository.lockById(bill.getId())).thenReturn(Optional.of(bill));
        when(payPaymentClient.createDirectPayment(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PayPaymentClient.PaymentRef(UUID.randomUUID(), new BigDecimal("49.00"),
                        "EGP", "SUCCEEDED", null, List.of()));

        assertThatThrownBy(() -> service.pay(bill.getId(), TENANT))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t))
                        .isEqualTo(NotraiusErrors.LEASE_BREAKDOWN_AMOUNT_MISMATCH));
        assertThat(bill.getStatus().getCode()).isEqualTo(NotraiusStatuses.LEASE_BREAKDOWN_PENDING);
    }

    @Test
    @DisplayName("a short cash claim is a recorded fact, not a paid bill")
    void shortCashDoesNotSettle() {
        LeaseBreakdown bill = bill(NotraiusStatuses.LEASE_BREAKDOWN_PENDING);
        when(breakdownRepository.lockById(bill.getId())).thenReturn(Optional.of(bill));
        LeaseCashConfirmRequest request = new LeaseCashConfirmRequest();
        request.setAmount(new BigDecimal("20.00"));

        LeaseCashConfirmResponse response = service.confirmCash(bill.getId(), OWNER, request);

        assertThat(response.isSettled()).isFalse();
        assertThat(response.getOutstanding()).isEqualByComparingTo("30.00");
        assertThat(bill.getStatus().getCode()).isEqualTo(NotraiusStatuses.LEASE_BREAKDOWN_PENDING);
        assertThat(bill.getSettledAt()).isNull();
    }

    @Test
    @DisplayName("cash that completes the due figure settles the row")
    void exactCashSettles() {
        LeaseBreakdown bill = bill(NotraiusStatuses.LEASE_BREAKDOWN_OVERDUE);
        when(breakdownRepository.lockById(bill.getId())).thenReturn(Optional.of(bill));
        LeaseCashConfirmRequest request = new LeaseCashConfirmRequest();
        request.setAmount(new BigDecimal("50.00"));

        LeaseCashConfirmResponse response = service.confirmCash(bill.getId(), OWNER, request);

        assertThat(response.isSettled()).isTrue();
        assertThat(response.getOutstanding()).isEqualByComparingTo("0");
        assertThat(bill.getStatus().getCode()).isEqualTo(NotraiusStatuses.LEASE_BREAKDOWN_SETTLED);
    }

    @Test
    @DisplayName("a claim above the bill refuses — the calendar has no credit ledger")
    void overClaimRefuses() {
        LeaseBreakdown bill = bill(NotraiusStatuses.LEASE_BREAKDOWN_PENDING);
        when(breakdownRepository.lockById(bill.getId())).thenReturn(Optional.of(bill));
        LeaseCashConfirmRequest request = new LeaseCashConfirmRequest();
        request.setAmount(new BigDecimal("80.00"));

        assertThatThrownBy(() -> service.confirmCash(bill.getId(), OWNER, request))
                .isInstanceOf(BadRequestException.class)
                .satisfies(t -> assertThat(errorOf(t))
                        .isEqualTo(NotraiusErrors.LEASE_BREAKDOWN_AMOUNT_MISMATCH));
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private LeaseBreakdown bill(String statusCode) {
        Property property = new Property();
        property.setId(UUID.randomUUID());
        property.setOwnerId(OWNER);
        property.setCurrencyId(UUID.randomUUID());
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
