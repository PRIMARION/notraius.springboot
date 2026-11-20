package com.notraius.modules.modules.lease.service.advance;

import com.notraius.common.integration.pay.PayPaymentClient;
import com.notraius.common.services.logs.StatusResolver;
import com.notraius.database.entities.asset.asset.Pricing;
import com.notraius.database.entities.asset.asset.Property;
import com.notraius.database.entities.asset.config.Policy;
import com.notraius.database.entities.asset.config.PolicyUnit;
import com.notraius.database.entities.lease.lease.records.Lease;
import com.notraius.database.entities.lease.lease.lease.LeaseDeposit;
import com.notraius.database.entities.lease.lease.lease.LeaseDepositSplit;
import com.notraius.database.entities.lease.lease.lease.LeaseTopUp;
import com.notraius.database.entities.lease.reference.amenity.Entities;
import com.notraius.database.entities.lease.reference.status.Status;
import com.notraius.database.entities.lease.reference.status.Type;
import com.notraius.database.repositories.lease.lease.lease.LeaseDepositRepository;
import com.notraius.database.repositories.lease.lease.lease.LeaseDepositSplitRepository;
import com.notraius.database.repositories.lease.lease.lease.LeaseRepository;
import com.notraius.database.repositories.lease.lease.records.LeaseTopUpRepository;
import com.notraius.modules.core.core.common.constants.NotraiusStatuses;
import com.notraius.modules.lease.lease.finance.dto.request.LeaseTopUpRequest;
import com.notraius.modules.lease.lease.state.LeaseStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.primarion.module.app.access.consent.AuditLogWriter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.notraius.modules.lease.lease.finance.LeaseFinanceService;
import com.notraius.modules.modules.lease.service.lease.agreement.LeaseFinanceServiceFixture;

/**
 * Restoring an AMOUNT cushion is not "one unit": the single global split
 * carries the whole standard, and the counter it feeds is month-equivalents.
 * The pre-fix code added {@code units} (1), so a restored three-month cushion
 * recorded as one month and the notice engine immediately called it thin.
 */
@ExtendWith(MockitoExtension.class)
class AdvanceTopUpServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CURRENCY = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID PAYMENT = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock private LeaseRepository leaseRepository;
    @Mock private LeaseDepositRepository depositRepository;
    @Mock private LeaseDepositSplitRepository splitRepository;
    @Mock private LeaseTopUpRepository topUpRepository;
    @Mock private PayPaymentClient payPaymentClient;
    @Mock private StatusResolver statusResolver;
    @Mock private LeaseStateService stateService;
    @Mock private AuditLogWriter auditLogWriter;

    private LeaseFinanceService service;

    @BeforeEach
    void setUp() {
        service = LeaseFinanceServiceFixture.aService()
                .leaseRepository(leaseRepository)
                .depositRepository(depositRepository)
                .splitRepository(splitRepository)
                .topUpRepository(topUpRepository)
                .payPaymentClient(payPaymentClient)
                .statusResolver(statusResolver)
                .stateService(stateService)
                .auditLogWriter(auditLogWriter)
                .build();
        lenient().when(statusResolver.resolve(any(), any()))
                .thenAnswer(invocation -> status(invocation.getArgument(1)));
        lenient().when(statusResolver.resolveEntity(any())).thenReturn(entity());
        lenient().when(topUpRepository.save(any(LeaseTopUp.class))).thenAnswer(i -> {
            LeaseTopUp saved = i.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID()); // the database assigns it in production
            }
            return saved;
        });
        lenient().when(splitRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("an AMOUNT top-up restores the whole frozen standard, not a single month")
    void amountTopUpRestoresTheStandard() {
        Property property = new Property();
        property.setId(UUID.randomUUID());
        property.setOwnerId(OWNER);
        property.setCurrencyId(CURRENCY);

        Pricing pricing = new Pricing();
        pricing.setId(UUID.randomUUID());
        pricing.setProperty(property);
        pricing.setAmount(new BigDecimal("1000.00"));

        Lease lease = new Lease();
        lease.setId(UUID.randomUUID());
        lease.setUserId(TENANT);
        lease.setOwnerId(OWNER);
        lease.setPricing(pricing);
        lease.setStatus(status(NotraiusStatuses.LEASE_ACTIVE));
        lease.setVersion(1);

        Policy policy = new Policy();
        policy.setId(UUID.randomUUID());
        policy.setCode("ADVANCE");
        policy.setUnit(PolicyUnit.AMOUNT);

        Type depositType = new Type();
        depositType.setId(UUID.randomUUID());
        depositType.setCode(NotraiusStatuses.LEASE_DEPOSIT_ADVANCE);

        LeaseDeposit advance = new LeaseDeposit();
        advance.setId(UUID.randomUUID());
        advance.setLease(lease);
        advance.setPolicy(policy);
        advance.setType(depositType);
        advance.setBalance(BigDecimal.ZERO);          // the cushion was consumed
        advance.setStandardBalance(new BigDecimal("3")); // three months at birth
        advance.setStatus(status(NotraiusStatuses.LEASE_DEPOSIT_HELD));
        advance.setVersion(1);

        LeaseDepositSplit born = new LeaseDepositSplit();
        born.setDeposit(advance);
        born.setAmount(new BigDecimal("3000.00"));
        born.setSequence(1);

        when(leaseRepository.findById(lease.getId())).thenReturn(Optional.of(lease));
        when(depositRepository.findByLeaseIdOrderByPolicyCodeAsc(lease.getId()))
                .thenReturn(List.of(advance));
        // The live policy now says 9,999: the top-up must still price the
        // 3,000 this lease actually funded, not today's offer.
        when(splitRepository.findByLeaseIdOrderBySequenceAsc(lease.getId())).thenReturn(List.of(born));
        when(payPaymentClient.createCustodialMatrix(eq(TENANT), eq(CURRENCY), anyString(), any(), anyList()))
                .thenReturn(new PayPaymentClient.PaymentRef(PAYMENT, new BigDecimal("3000.00"),
                        "EUR", "CAPTURED", "HELD", List.of(
                        new PayPaymentClient.SplitInfo(UUID.randomUUID(), OWNER,
                                new BigDecimal("3000.00"), "ADVANCE"))));

        LeaseTopUpRequest request = new LeaseTopUpRequest();
        request.setUnits(1);

        service.topUp(lease.getId(), TENANT, request);

        assertThat(advance.getBalance()).isEqualByComparingTo("3");
        ArgumentCaptor<LeaseTopUp> saved = ArgumentCaptor.forClass(LeaseTopUp.class);
        verify(topUpRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().getStatus().getCode()).isEqualTo(NotraiusStatuses.LEASE_TOPUP_FUNDED);
        assertThat(saved.getValue().getAmount()).isEqualByComparingTo("3000.00");
        verify(stateService).evaluateDeposit(lease);
    }

    private Status status(String code) {
        Status status = new Status();
        status.setId(UUID.randomUUID());
        status.setCode(code);
        return status;
    }

    private Entities entity() {
        Entities entity = new Entities();
        entity.setId(UUID.randomUUID());
        return entity;
    }
}
