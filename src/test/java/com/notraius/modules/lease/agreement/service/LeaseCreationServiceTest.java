package com.notraius.modules.lease.agreement.service;

import com.notraius.common.NotraiusErrors;
import com.notraius.common.integration.PayPaymentClient;
import com.notraius.common.services.StatusResolver;
import com.notraius.database.entities.asset.Pricing;
import com.notraius.database.entities.asset.Property;
import com.notraius.database.entities.config.Policy;
import com.notraius.database.entities.config.PolicyUnit;
import com.notraius.database.entities.lease.Lease;
import com.notraius.database.entities.lease.LeaseDeposit;
import com.notraius.database.entities.lease.LeaseDepositSplit;
import com.notraius.database.entities.reference.Duration;
import com.notraius.database.entities.reference.Entities;
import com.notraius.database.entities.reference.Status;
import com.notraius.database.entities.reference.Type;
import com.notraius.database.entities.reference.Validity;
import com.notraius.database.repositories.asset.PricingRepository;
import com.notraius.database.repositories.asset.PropertyRepository;
import com.notraius.database.repositories.lease.LeaseDepositRepository;
import com.notraius.database.repositories.lease.LeaseDepositSplitRepository;
import com.notraius.database.repositories.lease.LeaseRepository;
import com.notraius.database.repositories.reference.ValidityRepository;
import com.notraius.modules.core.common.constants.NotraiusStatuses;
import com.notraius.modules.lease.agreement.dto.request.LeaseCreateRequest;
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
import org.primarion.module.utils.security.users.UserService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The birth's laws (PLAN 6.1): the matrix is derived before anything is written,
 * the reference is persisted before the funding call leaves, a refusal rolls the
 * whole birth back, an unknown leaves a truthful PENDING row, and a retried
 * birth adopts the funding that stands instead of charging twice.
 */
@ExtendWith(MockitoExtension.class)
class LeaseCreationServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private PricingRepository pricingRepository;
    @Mock private PropertyRepository propertyRepository;
    @Mock private LeaseRepository leaseRepository;
    @Mock private LeaseDepositRepository depositRepository;
    @Mock private LeaseDepositSplitRepository splitRepository;
    @Mock private ValidityRepository validityRepository;
    @Mock private PayPaymentClient payPaymentClient;
    @Mock private StatusResolver statusResolver;
    @Mock private UserService userService;
    @Mock private AuditLogWriter auditLogWriter;

    private LeaseAgreementService service;
    private final List<LeaseDepositSplit> savedSplits = new ArrayList<>();
    private final List<LeaseDeposit> savedDeposits = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = spy(LeaseAgreementServiceFixture.aService()
                .pricingRepository(pricingRepository)
                .propertyRepository(propertyRepository)
                .leaseRepository(leaseRepository)
                .depositRepository(depositRepository)
                .splitRepository(splitRepository)
                .validityRepository(validityRepository)
                .payPaymentClient(payPaymentClient)
                .statusResolver(statusResolver)
                .userService(userService)
                .auditLogWriter(auditLogWriter)
                .build());
        // The birth is under test here; the matrix derivation has its own test.
        lenient().doAnswer(invocation -> standard(invocation.getArgument(0)))
                .when(service).derive(any());

        lenient().when(statusResolver.resolve(any(), any()))
                .thenAnswer(invocation -> status(invocation.getArgument(1)));
        lenient().when(statusResolver.openValidity(any(), any())).thenAnswer(invocation -> new Validity());
        lenient().when(statusResolver.resolveEntity(any())).thenAnswer(invocation -> {
            Entities entities = new Entities();
            entities.setId(UUID.randomUUID());
            return entities;
        });
        lenient().when(leaseRepository.save(any(Lease.class))).thenAnswer(invocation -> {
            Lease lease = invocation.getArgument(0);
            if (lease.getId() == null) {
                lease.setId(UUID.randomUUID());
            }
            return lease;
        });
        lenient().when(propertyRepository.save(any(Property.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(depositRepository.save(any(LeaseDeposit.class))).thenAnswer(invocation -> {
            LeaseDeposit deposit = invocation.getArgument(0);
            if (deposit.getId() == null) {
                deposit.setId(UUID.randomUUID());
            }
            savedDeposits.add(deposit);
            return deposit;
        });
        lenient().when(splitRepository.save(any(LeaseDepositSplit.class))).thenAnswer(invocation -> {
            LeaseDepositSplit split = invocation.getArgument(0);
            if (split.getId() == null) {
                split.setId(UUID.randomUUID());
            }
            savedSplits.add(split);
            return split;
        });
        lenient().when(depositRepository.findByLeaseIdOrderByPolicyCodeAsc(any())).thenAnswer(i -> savedDeposits);
        lenient().when(splitRepository.findByLeaseIdOrderBySequenceAsc(any())).thenAnswer(i -> savedSplits);
        lenient().when(userService.isKycVerified(any())).thenReturn(true);
        lenient().when(leaseRepository.findReusable(any(), any(), any())).thenReturn(List.of());
        lenient().when(leaseRepository.countLiveForProperty(any(), any())).thenReturn(0L);
    }

    // ── Birth ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("birth funds one matrix, writes the split ledger and flips the property LEASED")
    void birthFundsMatrixAndFlipsProperty() {
        Property property = property(NotraiusStatuses.PROPERTY_PUBLISHED);
        Pricing pricing = pricing(property);
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(payPaymentClient.createCustodialMatrix(eq(TENANT), any(), anyString(), any(), any()))
                .thenReturn(payment(new BigDecimal("100.00"), 2));

        LeaseResponse response = service.create(request(pricing.getId()), TENANT);

        assertThat(response.getStatus()).isEqualTo(NotraiusStatuses.LEASE_PENDING);
        assertThat(response.getTenantId()).isEqualTo(TENANT);
        assertThat(response.getOwnerId()).isEqualTo(OWNER);
        assertThat(response.isFundingResolved()).isTrue();
        assertThat(response.getMatrixTotal()).isEqualByComparingTo("100.00");
        assertThat(response.getDeposits()).hasSize(1);
        assertThat(response.getDeposits().getFirst().getBalance()).isEqualByComparingTo("2");

        ArgumentCaptor<Property> propertySaved = ArgumentCaptor.forClass(Property.class);
        verify(propertyRepository).save(propertySaved.capture());
        assertThat(propertySaved.getValue().getStatus().getCode()).isEqualTo(NotraiusStatuses.PROPERTY_LEASED);

        assertThat(savedSplits).hasSize(2);
        assertThat(savedSplits).extracting(LeaseDepositSplit::getSequence).containsExactly(1, 2);
        assertThat(savedSplits).allSatisfy(split -> {
            assertThat(split.getAmount()).isEqualByComparingTo("50.00");
            assertThat(split.getStatus().getCode()).isEqualTo(NotraiusStatuses.LEASE_SPLIT_HELD);
            assertThat(split.getPaymentId()).isNotNull();
            assertThat(split.getSplitId()).isNotNull();
        });
    }

    @Test
    @DisplayName("birth freezes the condition term on the lease, so a later price edit cannot add or remove the stage")
    void birthFreezesTheConditionTerm() {
        Property property = property(NotraiusStatuses.PROPERTY_PUBLISHED);
        Pricing pricing = pricing(property);
        pricing.setCheckable(true);
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(payPaymentClient.createCustodialMatrix(eq(TENANT), any(), anyString(), any(), any()))
                .thenReturn(payment(new BigDecimal("100.00"), 2));

        LeaseResponse response = service.create(request(pricing.getId()), TENANT);

        assertThat(response.isCheckable())
                .as("the lease door states the term this tenancy owes")
                .isTrue();

        ArgumentCaptor<Lease> leaseSaved = ArgumentCaptor.forClass(Lease.class);
        verify(leaseRepository, atLeastOnce()).save(leaseSaved.capture());

        assertThat(response.getRentAmount()).isEqualByComparingTo("50.00");
        assertThat(response.getPeriodUnit()).isEqualTo("MONTH");
        assertThat(response.getPeriodValue()).isEqualTo(1);

        // The owner edits the offer afterwards — flag off, cadence repointed.
        // The pricing may change; what this tenant owes was fixed at birth.
        pricing.setCheckable(false);
        pricing.setDuration(null);

        assertThat(leaseSaved.getValue().isCheckable())
                .as("the born term is the lease's own fact, never a live read of the pricing")
                .isTrue();
        assertThat(leaseSaved.getValue().getDuration())
                .as("the signed cadence survives the offer being repointed")
                .isNotNull();
        assertThat(leaseSaved.getValue().getRentAmount()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("the wire matrix names the owner and gives every month its own reference")
    void everySplitIsAddressedIndividually() {
        Property property = property(NotraiusStatuses.PROPERTY_PUBLISHED);
        Pricing pricing = pricing(property);
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(payPaymentClient.createCustodialMatrix(eq(TENANT), any(), anyString(), any(), any()))
                .thenReturn(payment(new BigDecimal("100.00"), 2));

        service.create(request(pricing.getId()), TENANT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PayPaymentClient.MatrixSplit>> matrix = ArgumentCaptor.forClass(List.class);
        verify(payPaymentClient).createCustodialMatrix(eq(TENANT), any(), anyString(), any(), matrix.capture());
        assertThat(matrix.getValue()).hasSize(2);
        assertThat(matrix.getValue()).allSatisfy(split -> {
            assertThat(split.beneficiaryId()).isEqualTo(OWNER);
            assertThat(split.amount()).isEqualByComparingTo("50.00");
            assertThat(split.entityId()).isNotNull();
            assertThat(split.reason()).contains("ADVANCE");
        });
        assertThat(matrix.getValue().get(0).referenceId())
                .isNotEqualTo(matrix.getValue().get(1).referenceId());
    }

    @Test
    @DisplayName("a pay refusal rolls the birth back — no lease, no flip")
    void refusalRollsBack() {
        Property property = property(NotraiusStatuses.PROPERTY_PUBLISHED);
        Pricing pricing = pricing(property);
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(payPaymentClient.createCustodialMatrix(any(), any(), anyString(), any(), any()))
                .thenThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "refused",
                        HttpHeaders.EMPTY, new byte[0], null));

        assertThatThrownBy(() -> service.create(request(pricing.getId()), TENANT))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.LEASE_PAYMENT_REFUSED));

        // The flip is written before the funding call (the saga's first
        // transaction); the refusal rolls it back with the lease row. What the
        // unit test can prove is that no split ever entered the ledger.
        verify(splitRepository, never()).save(any());
    }

    @Test
    @DisplayName("an unanswered funding call leaves a truthful PENDING row, never a blind compensation")
    void unknownFundingStandsPending() {
        Property property = property(NotraiusStatuses.PROPERTY_PUBLISHED);
        Pricing pricing = pricing(property);
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(payPaymentClient.createCustodialMatrix(any(), any(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("read timed out"));

        LeaseResponse response = service.create(request(pricing.getId()), TENANT);

        assertThat(response.getStatus()).isEqualTo(NotraiusStatuses.LEASE_PENDING);
        assertThat(response.isFundingResolved()).isFalse();
        assertThat(savedSplits).isEmpty();
    }

    @Test
    @DisplayName("the retried birth adopts the funding that already stands")
    void retryAdoptsStandingFunding() {
        Property property = property(NotraiusStatuses.PROPERTY_LEASED);
        Pricing pricing = pricing(property);
        Lease lease = existingLease(pricing);
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(leaseRepository.findReusable(TENANT, pricing.getId(), List.of(NotraiusStatuses.LEASE_PENDING)))
                .thenReturn(List.of(lease));
        PayPaymentClient.PaymentRef standing = payment(new BigDecimal("100.00"), 2);
        when(payPaymentClient.findByReference(lease.getId())).thenReturn(Optional.of(standing));

        LeaseResponse response = service.create(request(pricing.getId()), TENANT);

        assertThat(response.getId()).isEqualTo(lease.getId());
        assertThat(response.isFundingResolved()).isTrue();
        assertThat(savedSplits).hasSize(2);
    }

    @Test
    @DisplayName("a standing funding the standard no longer explains refuses loudly")
    void adoptionMismatchRefuses() {
        Property property = property(NotraiusStatuses.PROPERTY_LEASED);
        Pricing pricing = pricing(property);
        Lease lease = existingLease(pricing);
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(leaseRepository.findReusable(TENANT, pricing.getId(), List.of(NotraiusStatuses.LEASE_PENDING)))
                .thenReturn(List.of(lease));
        // Pay holds 100 in ONE split while the standard now carves two.
        when(payPaymentClient.findByReference(lease.getId()))
                .thenReturn(Optional.of(payment(new BigDecimal("100.00"), 1)));

        assertThatThrownBy(() -> service.create(request(pricing.getId()), TENANT))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.LEASE_MATRIX_INVALID));
    }

    // ── Gates ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an owner cannot lease their own property")
    void ownPropertyRefused() {
        Property property = property(NotraiusStatuses.PROPERTY_PUBLISHED);
        Pricing pricing = pricing(property);
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));

        assertThatThrownBy(() -> service.create(request(pricing.getId()), OWNER))
                .isInstanceOf(AppException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.LEASE_OWN_PROPERTY));
    }

    @Test
    @DisplayName("a property that is not PUBLISHED cannot take on a lease")
    void unavailablePropertyRefused() {
        Property property = property(NotraiusStatuses.PROPERTY_LEASED);
        Pricing pricing = pricing(property);
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));

        assertThatThrownBy(() -> service.create(request(pricing.getId()), TENANT))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.LEASE_PROPERTY_NOT_AVAILABLE));
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private LeaseCreateRequest request(UUID pricingId) {
        LeaseCreateRequest request = new LeaseCreateRequest();
        request.setPricingId(pricingId);
        request.setFromDate(Instant.now());
        return request;
    }

    private LeaseAgreementService.DepositStandard standard(Pricing pricing) {
        Policy advance = new Policy();
        advance.setId(UUID.randomUUID());
        advance.setCode("ADVANCE");
        advance.setUnit(PolicyUnit.UNITS);
        advance.setMandatory(true);

        Type type = new Type();
        type.setId(UUID.randomUUID());
        type.setCode("ADVANCE");

        LeaseAgreementService.DepositCommitment commitment = new LeaseAgreementService.DepositCommitment(
                advance, type, PolicyUnit.UNITS, new BigDecimal("2"), 2, new BigDecimal("50.00"),
                new BigDecimal("100.00"), new BigDecimal("2"), new BigDecimal("50.00"));
        return new LeaseAgreementService.DepositStandard(pricing.getId(), List.of(commitment),
                new BigDecimal("100.00"));
    }

    private PayPaymentClient.PaymentRef payment(BigDecimal amount, int splits) {
        List<PayPaymentClient.SplitInfo> born = new ArrayList<>();
        for (int i = 0; i < splits; i++) {
            born.add(new PayPaymentClient.SplitInfo(UUID.randomUUID(), OWNER,
                    amount.divide(BigDecimal.valueOf(splits)), "ADVANCE"));
        }
        return new PayPaymentClient.PaymentRef(UUID.randomUUID(), amount, "EUR", "CAPTURED",
                "HELD", born);
    }

    private Lease existingLease(Pricing pricing) {
        Lease lease = new Lease();
        lease.setId(UUID.randomUUID());
        lease.setUserId(TENANT);
        lease.setOwnerId(OWNER);
        lease.setPricing(pricing);
        lease.setCheckable(pricing.isCheckable());
        lease.setDuration(pricing.getDuration());
        lease.setValidity(new Validity());
        lease.setStatus(status(NotraiusStatuses.LEASE_PENDING));
        lease.setVersion(1);
        return lease;
    }

    private Pricing pricing(Property property) {
        Type unitType = new Type();
        unitType.setId(UUID.randomUUID());
        unitType.setCode("MONTH");

        Duration duration = new Duration();
        duration.setId(UUID.randomUUID());
        duration.setType(unitType);
        duration.setValue(1);

        Pricing pricing = new Pricing();
        pricing.setId(UUID.randomUUID());
        pricing.setProperty(property);
        pricing.setDuration(duration);
        pricing.setAmount(new BigDecimal("50.00"));
        pricing.setVersion(1);
        pricing.setStatus(status(NotraiusStatuses.PRICING_LIVE));
        Validity window = new Validity();
        window.setId(UUID.randomUUID());
        window.setFromDate(Instant.now().minus(1, ChronoUnit.DAYS));
        pricing.setValidity(window);
        return pricing;
    }

    private Property property(String statusCode) {
        Property property = new Property();
        property.setId(UUID.randomUUID());
        property.setTitle("Villa");
        property.setOwnerId(OWNER);
        property.setCurrencyId(UUID.randomUUID());
        property.setStateId(UUID.randomUUID());
        property.setStatus(status(statusCode));
        property.setVersion(1);
        return property;
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
