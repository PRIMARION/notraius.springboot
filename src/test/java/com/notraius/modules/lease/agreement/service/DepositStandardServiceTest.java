package com.notraius.modules.lease.agreement.service;

import com.notraius.common.NotraiusErrors;
import com.notraius.common.services.PolicyResolver;
import com.notraius.database.entities.asset.Pricing;
import com.notraius.database.entities.asset.Property;
import com.notraius.database.entities.asset.PropertyPolicyValue;
import com.notraius.database.entities.config.Policy;
import com.notraius.database.entities.config.PolicyUnit;
import com.notraius.database.entities.reference.Duration;
import com.notraius.database.entities.reference.Status;
import com.notraius.database.entities.reference.Type;
import com.notraius.database.repositories.asset.PropertyPolicyValueRepository;
import com.notraius.database.repositories.reference.TypeRepository;
import com.notraius.modules.core.common.constants.NotraiusStatuses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.primarion.module.errors.AppException;
import org.primarion.module.errors.ErrorCode;
import org.primarion.module.errors.ConflictException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * The operator's dynamic rule (PLAN 6.1/6.4): a UNITS standard carves one split
 * per unit of the pricing's cadence, an AMOUNT standard is one global split, and
 * neither is a hardcoded ADVANCE branch. The counter is months-of-thing; the
 * money is the escrow sums; nothing is funded against a hole.
 */
@ExtendWith(MockitoExtension.class)
class DepositStandardServiceTest {

    private static final UUID STATE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock private PolicyResolver policyResolver;
    @Mock private PropertyPolicyValueRepository valueRepository;
    @Mock private TypeRepository typeRepository;

    private LeaseAgreementService service;
    private Pricing pricing;
    private final List<PropertyPolicyValue> values = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = LeaseAgreementServiceFixture.aService()
                .policyResolver(policyResolver).valueRepository(valueRepository)
                .typeRepository(typeRepository).build();
        pricing = pricing(new BigDecimal("100.00"), "MONTH", 1);
        values.clear();
        lenient().when(valueRepository.findByPricingId(pricing.getId())).thenAnswer(i -> values);
    }

    @Test
    @DisplayName("four UNITS months carve four splits; one AMOUNT figure is one global split")
    void unitsCarvePerUnitAndAmountIsGlobal() {
        Policy advance = policy("ADVANCE", PolicyUnit.UNITS, true);
        Policy security = policy("SECURITY", PolicyUnit.AMOUNT, true);
        givenPolicies(advance, security);
        givenValue(advance, new BigDecimal("4"), NotraiusStatuses.POLICY_VALUE_ACTIVE);
        givenValue(security, new BigDecimal("300.00"), NotraiusStatuses.POLICY_VALUE_ACTIVE);
        givenDepositType("ADVANCE");
        givenDepositType("SECURITY");

        LeaseAgreementService.DepositStandard standard = service.derive(pricing);

        assertThat(standard.commitments()).hasSize(2);
        LeaseAgreementService.DepositCommitment advanceRow = commitment(standard, "ADVANCE");
        assertThat(advanceRow.splitCount()).isEqualTo(4);
        assertThat(advanceRow.splitAmount()).isEqualByComparingTo("100.00");
        assertThat(advanceRow.total()).isEqualByComparingTo("400.00");
        assertThat(advanceRow.balance()).isEqualByComparingTo("4");

        LeaseAgreementService.DepositCommitment securityRow = commitment(standard, "SECURITY");
        assertThat(securityRow.splitCount()).isEqualTo(1);
        assertThat(securityRow.total()).isEqualByComparingTo("300.00");
        // One global split over a 100/month rent covers three whole months.
        assertThat(securityRow.balance()).isEqualByComparingTo("3");

        assertThat(standard.splitCount()).isEqualTo(5);
        assertThat(standard.total()).isEqualByComparingTo("700.00");
    }

    @Test
    @DisplayName("a nightly cadence converts through the duration chain, never by assuming a month")
    void nightlyCadenceConvertsToMonths() {
        pricing = pricing(new BigDecimal("10.00"), "DAY", 1);
        Policy advance = policy("ADVANCE", PolicyUnit.UNITS, true);
        givenPolicies(advance);
        givenValue(advance, new BigDecimal("2"), NotraiusStatuses.POLICY_VALUE_ACTIVE);
        givenDepositType("ADVANCE");

        LeaseAgreementService.DepositStandard standard = service.derive(pricing);

        // Two nights of advance: two splits at the nightly rent, counter two.
        assertThat(standard.commitments().getFirst().splitCount()).isEqualTo(2);
        assertThat(standard.total()).isEqualByComparingTo("20.00");
        assertThat(standard.commitments().getFirst().rentPerMonth()).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("a mandatory standard with no value fails closed and names the standard")
    void missingMandatoryStandardRefuses() {
        Policy advance = policy("ADVANCE", PolicyUnit.UNITS, true);
        givenPolicies(advance);

        assertThatThrownBy(() -> service.derive(pricing))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.LEASE_STANDARD_MISSING));
    }

    @Test
    @DisplayName("an optional standard nobody valued is simply absent")
    void optionalStandardIsSkipped() {
        Policy security = policy("SECURITY", PolicyUnit.AMOUNT, false);
        givenPolicies(security);

        assertThat(service.derive(pricing).commitments()).isEmpty();
    }

    @Test
    @DisplayName("a value the admin's bounds now flag refuses loudly — no custody priced by a disputed figure")
    void flaggedValueRefuses() {
        Policy advance = policy("ADVANCE", PolicyUnit.UNITS, true);
        givenPolicies(advance);
        givenValue(advance, new BigDecimal("4"), NotraiusStatuses.POLICY_VALUE_FLAGGED);

        assertThatThrownBy(() -> service.derive(pricing))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.LEASE_STANDARD_FLAGGED));
    }

    @Test
    @DisplayName("half a split does not exist: a fractional UNITS value refuses")
    void fractionalUnitsRefuse() {
        Policy advance = policy("ADVANCE", PolicyUnit.UNITS, true);
        givenPolicies(advance);
        givenValue(advance, new BigDecimal("2.5"), NotraiusStatuses.POLICY_VALUE_ACTIVE);
        givenDepositType("ADVANCE");

        assertThatThrownBy(() -> service.derive(pricing))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.LEASE_MATRIX_INVALID));
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private void givenPolicies(Policy... policies) {
        when(policyResolver.applicable(STATE)).thenReturn(List.of(policies));
    }

    private void givenValue(Policy policy, BigDecimal value, String statusCode) {
        PropertyPolicyValue row = new PropertyPolicyValue();
        row.setId(UUID.randomUUID());
        row.setPricing(pricing);
        row.setPolicy(policy);
        row.setValue(value);
        row.setStatus(status(statusCode));
        values.add(row);
        // A test may have re-priced the fixture after setUp; keep the stub on
        // the pricing under test.
        lenient().when(valueRepository.findByPricingId(pricing.getId())).thenAnswer(i -> values);
    }

    private void givenDepositType(String code) {
        Type type = new Type();
        type.setId(UUID.randomUUID());
        type.setCode(code);
        when(typeRepository.findByEntityCodeAndCode(any(), any())).thenReturn(Optional.of(type));
    }

    private static LeaseAgreementService.DepositCommitment commitment(
            LeaseAgreementService.DepositStandard standard, String code) {
        return standard.commitments().stream()
                .filter(row -> row.policy().getCode().equals(code))
                .findFirst().orElseThrow();
    }

    private Pricing pricing(BigDecimal amount, String unit, int count) {
        Property property = new Property();
        property.setId(UUID.randomUUID());
        property.setTitle("Villa");
        property.setStateId(STATE);

        Type unitType = new Type();
        unitType.setId(UUID.randomUUID());
        unitType.setCode(unit);

        Duration duration = new Duration();
        duration.setId(UUID.randomUUID());
        duration.setType(unitType);
        duration.setValue(count);

        Pricing row = new Pricing();
        row.setId(UUID.randomUUID());
        row.setProperty(property);
        row.setDuration(duration);
        row.setAmount(amount);
        return row;
    }

    private Policy policy(String code, PolicyUnit unit, boolean mandatory) {
        Type type = new Type();
        type.setId(UUID.randomUUID());
        type.setCode("INSTALLMENT");

        Policy policy = new Policy();
        policy.setId(UUID.randomUUID());
        policy.setCode(code);
        policy.setUnit(unit);
        policy.setMandatory(mandatory);
        policy.setType(type);
        return policy;
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
