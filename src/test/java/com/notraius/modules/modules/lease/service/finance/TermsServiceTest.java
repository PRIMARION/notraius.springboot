package com.notraius.modules.modules.lease.service.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notraius.common.audit.AuditTrail;
import com.notraius.common.NotraiusErrors;
import com.notraius.common.services.policy.PolicyResolver;
import com.notraius.common.services.logs.StatusResolver;
import com.notraius.database.entities.asset.asset.Pricing;
import com.notraius.database.entities.asset.asset.Property;
import com.notraius.database.entities.asset.asset.PropertyPolicyValue;
import com.notraius.database.entities.asset.asset.PropertyRuleSelection;
import com.notraius.database.entities.asset.config.Policy;
import com.notraius.database.entities.asset.config.PolicyUnit;
import com.notraius.database.entities.asset.config.Rule;
import com.notraius.database.entities.asset.config.RuleVisibility;
import com.notraius.database.entities.lease.reference.amenity.Entities;
import com.notraius.database.entities.lease.reference.status.Status;
import com.notraius.database.entities.lease.reference.amenity.Validity;
import com.notraius.database.repositories.asset.asset.PricingRepository;
import com.notraius.database.repositories.asset.asset.PropertyPolicyValueRepository;
import com.notraius.database.repositories.asset.asset.PropertyRepository;
import com.notraius.database.repositories.asset.asset.PropertyRuleSelectionRepository;
import com.notraius.database.repositories.asset.config.PolicyRepository;
import com.notraius.database.repositories.asset.config.RuleRepository;
import com.notraius.database.repositories.lease.reference.communication.ValidityRepository;
import com.notraius.modules.lease.asset.terms.dto.request.PolicyValueSetRequest;
import com.notraius.modules.lease.asset.terms.dto.request.RuleSelectRequest;
import com.notraius.modules.lease.asset.terms.dto.response.PolicyValueResponse;
import com.notraius.modules.lease.asset.terms.dto.response.RuleSelectionResponse;
import com.notraius.modules.lease.asset.terms.TermsMapper;
import com.notraius.modules.core.core.common.constants.NotraiusStatuses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.notraius.common.audit.RequiredAuditWriter;
import com.notraius.common.dto.RevisionSummaryResponse;
import com.notraius.common.services.visit.RevisionService;
import org.primarion.module.communication.errors.exception.AppException;
import org.primarion.module.communication.errors.client.ConflictException;
import org.primarion.module.communication.errors.core.ErrorCode;
import org.primarion.module.communication.errors.client.ForbiddenException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.primarion.module.communication.errors.client.NotFoundException;
import com.notraius.config.notraius.properties.NotraiusProperties;
import com.notraius.modules.lease.asset.terms.TermsService;

/**
 * Stress check for the terms sub-module (PLAN.md 4.3): a value lives inside its
 * standard's bounds and only against a standard that governs the property's
 * world, mandatory standards are verified at publication, and a rule selection
 * owns its window exclusively and is forgotten whole.
 */
@ExtendWith(MockitoExtension.class)
class TermsServiceTest {

    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STRANGER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID STATE = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock private PropertyRepository propertyRepository;
    @Mock private PricingRepository pricingRepository;
    @Mock private PropertyPolicyValueRepository valueRepository;
    @Mock private PropertyRuleSelectionRepository selectionRepository;
    @Mock private RuleRepository ruleRepository;
    @Mock private PolicyRepository policyRepository;
    @Mock private ValidityRepository validityRepository;
    @Mock private PolicyResolver policyResolver;
    @Mock private StatusResolver statusResolver;
    @Mock private RequiredAuditWriter auditLogWriter;
    @Mock private RevisionService revisionService;

    private TermsService service;

    @BeforeEach
    void setUp() {
        service = new TermsService(propertyRepository, pricingRepository, valueRepository, selectionRepository,
                ruleRepository, policyRepository, validityRepository, policyResolver, statusResolver,
                new TermsMapper(), revisionService,
                new AuditTrail(new ObjectMapper().findAndRegisterModules(), auditLogWriter),
                new com.notraius.config.notraius.properties.NotraiusProperties());
        lenient().when(statusResolver.resolve(any(), any()))
                .thenAnswer(invocation -> status(invocation.getArgument(1)));
        lenient().when(statusResolver.openValidity(any(), any())).thenAnswer(invocation -> openValidity());
    }

    // ── Values ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a number below the standard's minimum is refused, not flagged")
    void belowMinimumIsRefused() {
        Pricing pricing = pricing(OWNER);
        Policy policy = policy(NotraiusStatuses.POLICY_ENABLED);
        // A whole number below the floor: 0.5 would trip the counting rule first,
        // and this test is about the bounds.
        policy.setMinimum(new BigDecimal("2"));
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(policyResolver.applicable(policy.getId(), STATE)).thenReturn(Optional.of(policy));

        assertThatThrownBy(() -> service.setValue(pricing.getId(),
                new PolicyValueSetRequest(policy.getId(), new BigDecimal("1")), OWNER))
                .isInstanceOf(AppException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.TERM_VALUE_OUT_OF_BOUNDS));
        verify(valueRepository, never()).save(any());
    }

    @Test
    @DisplayName("a number above the standard's maximum is refused, not clamped")
    void aboveMaximumIsRefused() {
        Pricing pricing = pricing(OWNER);
        Policy policy = policy(NotraiusStatuses.POLICY_ENABLED);
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(policyResolver.applicable(policy.getId(), STATE)).thenReturn(Optional.of(policy));

        assertThatThrownBy(() -> service.setValue(pricing.getId(),
                new PolicyValueSetRequest(policy.getId(), new BigDecimal("99")), OWNER))
                .isInstanceOf(AppException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.TERM_VALUE_OUT_OF_BOUNDS));
    }

    @Test
    @DisplayName("a negative number is not a commitment the system can hold")
    void negativeValueIsRefused() {
        Pricing pricing = pricing(OWNER);
        Policy policy = policy(NotraiusStatuses.POLICY_ENABLED);
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(policyResolver.applicable(policy.getId(), STATE)).thenReturn(Optional.of(policy));

        assertThatThrownBy(() -> service.setValue(pricing.getId(),
                new PolicyValueSetRequest(policy.getId(), new BigDecimal("-1")), OWNER))
                .isInstanceOf(AppException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.TERM_VALUE_INVALID));
    }

    @Test
    @DisplayName("a flagged mandatory number is not a complete term")
    void flaggedMandatoryValueStopsPublication() {
        Property property = property(OWNER);
        Pricing pricing = pricing(OWNER);
        Policy policy = policy(NotraiusStatuses.POLICY_ENABLED);
        PropertyPolicyValue flagged = value(pricing, policy, "3", NotraiusStatuses.POLICY_VALUE_FLAGGED);
        when(policyResolver.applicable(STATE)).thenReturn(List.of(policy));
        when(pricingRepository.findByPropertyId(property.getId())).thenReturn(List.of(pricing));
        when(valueRepository.findByPricingIdIn(any())).thenReturn(List.of(flagged));

        // Present is not complete: the lease matrix refuses a flagged number, so
        // a listing that published with one would be marketable and unrentable.
        assertThatThrownBy(() -> service.requireMandatoryValuesComplete(property))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.TERM_VALUE_REQUIRED));
    }

    @Test
    @DisplayName("a paused standard's values cannot be changed")
    void pausedStandardIsNotSettable() {
        Pricing pricing = pricing(OWNER);
        Policy policy = policy(NotraiusStatuses.POLICY_DISABLED);
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(policyResolver.applicable(policy.getId(), STATE)).thenReturn(Optional.empty());
        when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));

        assertThatThrownBy(() -> service.setValue(pricing.getId(),
                new PolicyValueSetRequest(policy.getId(), new BigDecimal("3")), OWNER))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.TERM_POLICY_DISABLED));
    }

    @Test
    @DisplayName("another state's standard does not govern this property")
    void foreignStandardIsNotApplicable() {
        Pricing pricing = pricing(OWNER);
        Policy policy = policy(NotraiusStatuses.POLICY_ENABLED);
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(policyResolver.applicable(policy.getId(), STATE)).thenReturn(Optional.empty());
        when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));

        assertThatThrownBy(() -> service.setValue(pricing.getId(),
                new PolicyValueSetRequest(policy.getId(), new BigDecimal("3")), OWNER))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.TERM_POLICY_NOT_APPLICABLE));
    }

    @Test
    @DisplayName("a first value is ACTIVE at version one")
    void firstValueIsActiveAtVersionOne() {
        Pricing pricing = pricing(OWNER);
        Policy policy = policy(NotraiusStatuses.POLICY_ENABLED);
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(policyResolver.applicable(policy.getId(), STATE)).thenReturn(Optional.of(policy));
        when(valueRepository.findByPricingIdAndPolicyId(pricing.getId(), policy.getId()))
                .thenReturn(Optional.empty());

        PolicyValueResponse response = service.setValue(pricing.getId(),
                new PolicyValueSetRequest(policy.getId(), new BigDecimal("3")), OWNER);

        assertThat(response.getValue()).isEqualByComparingTo("3");
        assertThat(response.getStatusCode()).isEqualTo(NotraiusStatuses.POLICY_VALUE_ACTIVE);
        assertThat(response.getVersion()).isEqualTo(1);
        verify(valueRepository).save(any(PropertyPolicyValue.class));
    }

    @Test
    @DisplayName("setting a number again rewrites the row and bumps its version")
    void secondSetBumpsVersion() {
        Pricing pricing = pricing(OWNER);
        Policy policy = policy(NotraiusStatuses.POLICY_ENABLED);
        PropertyPolicyValue existing = value(pricing, policy, "3", NotraiusStatuses.POLICY_VALUE_FLAGGED);
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(policyResolver.applicable(policy.getId(), STATE)).thenReturn(Optional.of(policy));
        when(valueRepository.findByPricingIdAndPolicyId(pricing.getId(), policy.getId()))
                .thenReturn(Optional.of(existing));

        PolicyValueResponse response = service.setValue(pricing.getId(),
                new PolicyValueSetRequest(policy.getId(), new BigDecimal("4")), OWNER);

        assertThat(response.getValue()).isEqualByComparingTo("4");
        assertThat(response.getVersion()).isEqualTo(2);
        assertThat(response.getStatusCode()).isEqualTo(NotraiusStatuses.POLICY_VALUE_ACTIVE);
    }

    @Test
    @DisplayName("a count of half a month never reaches the lease matrix")
    void fractionalUnitsAreRefused() {
        Pricing pricing = pricing(OWNER);
        Policy policy = policy(NotraiusStatuses.POLICY_ENABLED);
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(policyResolver.applicable(policy.getId(), STATE)).thenReturn(Optional.of(policy));

        // 2.5 months is inside [1, 12] and still unrepresentable: the matrix
        // multiplies by a whole number of installments, so the number is refused
        // where the owner can fix it instead of at the first lease.
        assertThatThrownBy(() -> service.setValue(pricing.getId(),
                new PolicyValueSetRequest(policy.getId(), new BigDecimal("2.5")), OWNER))
                .isInstanceOf(AppException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.TERM_VALUE_INVALID));
        verify(valueRepository, never()).save(any());
    }

    @Test
    @DisplayName("a commitment of zero funds nothing, whatever the bounds allow")
    void zeroIsRefused() {
        Pricing pricing = pricing(OWNER);
        Policy policy = policy(NotraiusStatuses.POLICY_ENABLED);
        policy.setUnit(PolicyUnit.AMOUNT);
        policy.setMinimum(null);
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(policyResolver.applicable(policy.getId(), STATE)).thenReturn(Optional.of(policy));

        assertThatThrownBy(() -> service.setValue(pricing.getId(),
                new PolicyValueSetRequest(policy.getId(), BigDecimal.ZERO), OWNER))
                .isInstanceOf(AppException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.TERM_VALUE_INVALID));
    }

    @Test
    @DisplayName("forgetting a number takes the row away and records the removal")
    void forgetValueRemovesTheRow() {
        Pricing pricing = pricing(OWNER);
        Policy policy = policy(NotraiusStatuses.POLICY_ENABLED);
        PropertyPolicyValue existing = value(pricing, policy, "3", NotraiusStatuses.POLICY_VALUE_ACTIVE);
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(valueRepository.findByPricingIdAndPolicyId(pricing.getId(), policy.getId()))
                .thenReturn(Optional.of(existing));

        service.forgetValue(pricing.getId(), policy.getId(), OWNER);

        verify(valueRepository).delete(existing);
        verify(auditLogWriter).writeRequired(eq(Entities.PROPERTY_POLICIES),
                eq("PROPERTY_POLICIES.FORGET"), eq(existing.getId()), eq("FORGET"), any(), any());
    }

    @Test
    @DisplayName("forgetting a number the pricing does not carry is not silently ignored")
    void forgetValueRequiresAValue() {
        Pricing pricing = pricing(OWNER);
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(valueRepository.findByPricingIdAndPolicyId(eq(pricing.getId()), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.forgetValue(pricing.getId(), UUID.randomUUID(), OWNER))
                .isInstanceOf(org.primarion.module.communication.errors.client.NotFoundException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.TERM_VALUE_NOT_SET));
        verify(valueRepository, never()).delete(any());
    }

    @Test
    @DisplayName("only the owner writes the property's terms")
    void strangerCannotSetValues() {
        Pricing pricing = pricing(OWNER);
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));

        assertThatThrownBy(() -> service.setValue(pricing.getId(),
                new PolicyValueSetRequest(UUID.randomUUID(), new BigDecimal("3")), STRANGER))
                .isInstanceOf(ForbiddenException.class);
    }

    // ── Publication gate ──────────────────────────────────────────────────

    @Test
    @DisplayName("a mandatory standard with no value stops publication")
    void missingMandatoryValueStopsPublication() {
        Property property = property(OWNER);
        Pricing pricing = pricing(OWNER);
        Policy policy = policy(NotraiusStatuses.POLICY_ENABLED);
        when(policyResolver.applicable(STATE)).thenReturn(List.of(policy));
        when(pricingRepository.findByPropertyId(property.getId())).thenReturn(List.of(pricing));
        when(valueRepository.findByPricingIdIn(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.requireMandatoryValuesComplete(property))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.TERM_VALUE_REQUIRED));
    }

    @Test
    @DisplayName("a mandatory standard that is valued lets publication through")
    void completeMandatoryValuesPass() {
        Property property = property(OWNER);
        Pricing pricing = pricing(OWNER);
        Policy policy = policy(NotraiusStatuses.POLICY_ENABLED);
        when(policyResolver.applicable(STATE)).thenReturn(List.of(policy));
        when(pricingRepository.findByPropertyId(property.getId())).thenReturn(List.of(pricing));
        when(valueRepository.findByPricingIdIn(any()))
                .thenReturn(List.of(value(pricing, policy, "1", NotraiusStatuses.POLICY_VALUE_ACTIVE)));

        assertThatCode(() -> service.requireMandatoryValuesComplete(property)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a non-mandatory standard needs no value to publish")
    void optionalStandardDoesNotBlock() {
        Property property = property(OWNER);
        Policy optional = policy(NotraiusStatuses.POLICY_ENABLED);
        optional.setMandatory(false);
        when(policyResolver.applicable(STATE)).thenReturn(List.of(optional));

        assertThatCode(() -> service.requireMandatoryValuesComplete(property)).doesNotThrowAnyException();
        verify(pricingRepository, never()).findByPropertyId(any());
    }

    // ── Rule selections ───────────────────────────────────────────────────

    @Test
    @DisplayName("someone else's private rule is not selectable")
    void foreignPrivateRuleIsRefused() {
        Property property = property(OWNER);
        Rule rule = rule(STRANGER, RuleVisibility.PRIVATE);
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));

        assertThatThrownBy(() -> service.selectRule(property.getId(),
                new RuleSelectRequest(rule.getId(), null, null), OWNER))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.TERM_RULE_NOT_SELECTABLE));
    }

    @Test
    @DisplayName("a disabled rule cannot be selected")
    void disabledRuleIsRefused() {
        Property property = property(OWNER);
        Rule rule = rule(OWNER, RuleVisibility.PUBLIC);
        rule.setStatus(status(NotraiusStatuses.RULE_DISABLED));
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));

        assertThatThrownBy(() -> service.selectRule(property.getId(),
                new RuleSelectRequest(rule.getId(), null, null), OWNER))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.TERM_RULE_NOT_LIVE));
    }

    @Test
    @DisplayName("the same rule may not own one instant twice")
    void overlappingSelectionIsRefused() {
        Property property = property(OWNER);
        Rule rule = rule(OWNER, RuleVisibility.PUBLIC);
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        PropertyRuleSelection existing = selection(property, rule, from, from.plus(30, ChronoUnit.DAYS),
                NotraiusStatuses.RULE_SELECTION_ACTIVE);
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));
        when(selectionRepository.findByPropertyId(property.getId())).thenReturn(List.of(existing));

        RuleSelectRequest request = new RuleSelectRequest(rule.getId(), from.plus(5, ChronoUnit.DAYS),
                from.plus(40, ChronoUnit.DAYS));

        assertThatThrownBy(() -> service.selectRule(property.getId(), request, OWNER))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.TERM_RULE_ALREADY_SELECTED));
    }

    @Test
    @DisplayName("back-to-back windows do not overlap: the boundary belongs to the new window")
    void adjacentWindowIsAllowed() {
        Property property = property(OWNER);
        Rule rule = rule(OWNER, RuleVisibility.PUBLIC);
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant boundary = from.plus(30, ChronoUnit.DAYS);
        PropertyRuleSelection existing = selection(property, rule, from, boundary,
                NotraiusStatuses.RULE_SELECTION_ACTIVE);
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));
        when(selectionRepository.findByPropertyId(property.getId())).thenReturn(List.of(existing));

        RuleSelectionResponse response = service.selectRule(property.getId(),
                new RuleSelectRequest(rule.getId(), boundary, null), OWNER);

        assertThat(response.getFromDate()).isEqualTo(boundary);
        assertThat(response.getVersion()).isEqualTo(1);
        verify(selectionRepository).save(any(PropertyRuleSelection.class));
    }

    @Test
    @DisplayName("forgetting a selection removes the selection and its window together")
    void forgetRemovesSelectionAndWindow() {
        Property property = property(OWNER);
        Rule rule = rule(OWNER, RuleVisibility.PUBLIC);
        Validity validity = openValidity();
        validity.setFromDate(Instant.now().minus(1, ChronoUnit.DAYS));
        PropertyRuleSelection existing = new PropertyRuleSelection();
        existing.setId(UUID.randomUUID());
        existing.setProperty(property);
        existing.setRule(rule);
        existing.setValidity(validity);
        existing.setVersion(1);
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(selectionRepository.findByIdAndPropertyId(existing.getId(), property.getId()))
                .thenReturn(Optional.of(existing));

        service.forgetRule(property.getId(), existing.getId(), OWNER);

        verify(selectionRepository).delete(existing);
        verify(validityRepository).delete(validity);
    }

    @Test
    @DisplayName("deleting a property releases every selection in one batch, each with its own entry")
    void releaseSelectionsBatchesTheDeletes() {
        Property property = property(OWNER);
        Rule rule = rule(OWNER, RuleVisibility.PUBLIC);
        Instant from = Instant.now().minus(30, ChronoUnit.DAYS);
        PropertyRuleSelection first = selection(property, rule, from, null,
                NotraiusStatuses.RULE_SELECTION_ACTIVE);
        PropertyRuleSelection second = selection(property, rule, from, null,
                NotraiusStatuses.RULE_SELECTION_ACTIVE);
        when(selectionRepository.findByPropertyId(property.getId()))
                .thenReturn(List.of(first, second));

        assertThat(service.releaseSelections(property.getId())).isEqualTo(2);

        // All selections leave in one flush (the FK lives on the selection, so the
        // windows can only go after), then their windows, then one entry each.
        verify(selectionRepository).deleteAll(List.of(first, second));
        verify(selectionRepository).flush();
        verify(validityRepository).delete(first.getValidity());
        verify(validityRepository).delete(second.getValidity());
        verify(auditLogWriter, times(2)).writeRequired(eq(Entities.PROPERTY_RULES),
                eq("PROPERTY_RULES.FORGET"), any(), eq("FORGET"), any(), any());
    }

    @Test
    @DisplayName("a property with no selections releases nothing and writes nothing")
    void releaseWithNoSelectionsIsANoop() {
        when(selectionRepository.findByPropertyId(any())).thenReturn(List.of());

        assertThat(service.releaseSelections(UUID.randomUUID())).isZero();
        verify(selectionRepository, never()).deleteAll(any());
        verify(selectionRepository, never()).flush();
        verifyNoInteractions(auditLogWriter);
    }

    @Test
    @DisplayName("a selection whose window ended reads EXPIRED while staying stored ACTIVE")
    void endedSelectionReadsExpired() {
        Property property = property(OWNER);
        Rule rule = rule(OWNER, RuleVisibility.PUBLIC);
        Instant from = Instant.now().minus(30, ChronoUnit.DAYS);
        Instant to = Instant.now().minus(2, ChronoUnit.DAYS);
        PropertyRuleSelection selection = selection(property, rule, from, to,
                NotraiusStatuses.RULE_SELECTION_ACTIVE);
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(selectionRepository.findByPropertyId(property.getId())).thenReturn(List.of(selection));

        List<RuleSelectionResponse> listed = service.listRuleSelections(property.getId(), OWNER);

        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).getStatusCode()).isEqualTo(NotraiusStatuses.RULE_SELECTION_EXPIRED);
        assertThat(selection.getValidity().getStatus().getCode())
                .isEqualTo(NotraiusStatuses.RULE_SELECTION_ACTIVE);
    }

    @Test
    @DisplayName("a selection made for next season does not read as applying today")
    void futureSelectionDoesNotApplyYet() {
        // Regression guard: the derivation read only the window's END, so a
        // selection whose window had not begun reported ACTIVE — a rule the
        // property had not selected into force yet.
        Property property = property(OWNER);
        Rule rule = rule(OWNER, RuleVisibility.PUBLIC);
        Instant from = Instant.now().plus(20, ChronoUnit.DAYS);
        Instant to = Instant.now().plus(90, ChronoUnit.DAYS);
        PropertyRuleSelection selection = selection(property, rule, from, to,
                NotraiusStatuses.RULE_SELECTION_ACTIVE);
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(selectionRepository.findByPropertyId(property.getId())).thenReturn(List.of(selection));

        assertThat(service.listRuleSelections(property.getId(), OWNER))
                .singleElement()
                .extracting(RuleSelectionResponse::getStatusCode)
                .isEqualTo(NotraiusStatuses.RULE_SELECTION_EXPIRED);
    }

    // ── Revision doors ────────────────────────────────────────────────────

    @Test
    @DisplayName("a value's own version is the live read, not a chain lookup")
    void valueAtItsOwnVersionStaysLive() {
        Pricing pricing = pricing(OWNER);
        Policy policy = policy(NotraiusStatuses.POLICY_ENABLED);
        PropertyPolicyValue value = value(pricing, policy, new BigDecimal("3"));
        value.setVersion(4);
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(propertyRepository.findById(pricing.getProperty().getId()))
                .thenReturn(Optional.of(pricing.getProperty()));
        when(valueRepository.findByPricingIdAndPolicyId(pricing.getId(), policy.getId()))
                .thenReturn(Optional.of(value));

        PolicyValueResponse response = service.getValue(pricing.getId(), policy.getId(), 4, OWNER);

        assertThat(response.getId()).isEqualTo(value.getId());
        assertThat(response.getVersion()).isEqualTo(4);
        verifyNoInteractions(revisionService);
    }

    @Test
    @DisplayName("a lower version of a value is answered from the chain, not from the row")
    void valueAtAnEarlierRevisionComesFromTheChain() {
        Pricing pricing = pricing(OWNER);
        Policy policy = policy(NotraiusStatuses.POLICY_ENABLED);
        PropertyPolicyValue value = value(pricing, policy, new BigDecimal("3"));
        value.setVersion(4);
        PolicyValueResponse snapshot = new PolicyValueResponse(value.getId(), pricing.getId(),
                policy.getId(), policy.getCode(), new BigDecimal("2"),
                NotraiusStatuses.POLICY_VALUE_ACTIVE, 3, value.getCreatedAt(), value.getUpdatedAt());
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(propertyRepository.findById(pricing.getProperty().getId()))
                .thenReturn(Optional.of(pricing.getProperty()));
        when(valueRepository.findByPricingIdAndPolicyId(pricing.getId(), policy.getId()))
                .thenReturn(Optional.of(value));
        when(revisionService.revisionOf(Entities.PROPERTY_POLICIES, value.getId(), 3,
                PolicyValueResponse.class)).thenReturn(snapshot);

        PolicyValueResponse response = service.getValue(pricing.getId(), policy.getId(), 3, OWNER);

        // The chain's number, not the row's: answering 3 with today's 3 would be
        // the one failure the revision door exists to prevent.
        assertThat(response.getValue()).isEqualByComparingTo("2");
        assertThat(response.getVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("a value's revisions are listed for whoever may read the value")
    void valueRevisionsAreListed() {
        Pricing pricing = pricing(OWNER);
        Policy policy = policy(NotraiusStatuses.POLICY_ENABLED);
        PropertyPolicyValue value = value(pricing, policy, new BigDecimal("3"));
        value.setVersion(2);
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(propertyRepository.findById(pricing.getProperty().getId()))
                .thenReturn(Optional.of(pricing.getProperty()));
        when(valueRepository.findByPricingIdAndPolicyId(pricing.getId(), policy.getId()))
                .thenReturn(Optional.of(value));
        when(revisionService.revisionsOf(Entities.PROPERTY_POLICIES, value.getId(), 2))
                .thenReturn(List.of(new RevisionSummaryResponse(2, Instant.now(), true)));

        List<RevisionSummaryResponse> revisions = service.listValueRevisions(pricing.getId(),
                policy.getId(), OWNER);

        assertThat(revisions).hasSize(1);
        assertThat(revisions.get(0).getVersion()).isEqualTo(2);
        assertThat(revisions.get(0).isCurrent()).isTrue();
    }

    @Test
    @DisplayName("a selection read at an earlier revision recomputes its status at read time")
    void historicalSelectionStatusIsDerivedAtReadTime() {
        Property property = property(OWNER);
        Rule rule = rule(OWNER, RuleVisibility.PUBLIC);
        PropertyRuleSelection current = selection(property, rule, Instant.now().minus(1, ChronoUnit.DAYS),
                null, NotraiusStatuses.RULE_SELECTION_ACTIVE);
        current.setVersion(3);
        // The snapshot froze ACTIVE, its window then ended: reading it back today
        // must answer what the live door would answer for that same version.
        RuleSelectionResponse snapshot = new RuleSelectionResponse(current.getId(), property.getId(),
                rule.getId(), Instant.now().minus(30, ChronoUnit.DAYS),
                Instant.now().minus(2, ChronoUnit.DAYS), NotraiusStatuses.RULE_SELECTION_ACTIVE,
                NotraiusStatuses.RULE_SELECTION_ACTIVE, 2, Instant.now());
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(selectionRepository.findByIdAndPropertyId(current.getId(), property.getId()))
                .thenReturn(Optional.of(current));
        when(revisionService.revisionOf(Entities.PROPERTY_RULES, current.getId(), 2,
                RuleSelectionResponse.class)).thenReturn(snapshot);

        RuleSelectionResponse response = service.getRuleSelection(property.getId(), current.getId(),
                2, OWNER);

        assertThat(response.getStatusCode()).isEqualTo(NotraiusStatuses.RULE_SELECTION_EXPIRED);
        assertThat(response.getStoredStatusCode()).isEqualTo(NotraiusStatuses.RULE_SELECTION_ACTIVE);
    }

    @Test
    @DisplayName("a legacy selection snapshot frozen EXPIRED reads ACTIVE once its window answers")
    void legacyFrozenStatusIsCorrectedOnRead() {
        Property property = property(OWNER);
        Rule rule = rule(OWNER, RuleVisibility.PUBLIC);
        PropertyRuleSelection current = selection(property, rule, Instant.now().minus(1, ChronoUnit.DAYS),
                null, NotraiusStatuses.RULE_SELECTION_ACTIVE);
        current.setVersion(2);
        // Written before storedStatusCode existed: only the derived word is there,
        // and it says EXPIRED although the window now contains the present.
        RuleSelectionResponse snapshot = new RuleSelectionResponse(current.getId(), property.getId(),
                rule.getId(), Instant.now().minus(1, ChronoUnit.DAYS), null,
                NotraiusStatuses.RULE_SELECTION_EXPIRED, null, 1, Instant.now());
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(selectionRepository.findByIdAndPropertyId(current.getId(), property.getId()))
                .thenReturn(Optional.of(current));
        when(revisionService.revisionOf(Entities.PROPERTY_RULES, current.getId(), 1,
                RuleSelectionResponse.class)).thenReturn(snapshot);

        RuleSelectionResponse response = service.getRuleSelection(property.getId(), current.getId(),
                1, OWNER);

        assertThat(response.getStatusCode()).isEqualTo(NotraiusStatuses.RULE_SELECTION_ACTIVE);
    }

    @Test
    @DisplayName("a selection's revisions are listed under the property's own visibility")
    void selectionRevisionsAreListed() {
        Property property = property(OWNER);
        Rule rule = rule(OWNER, RuleVisibility.PUBLIC);
        PropertyRuleSelection current = selection(property, rule, Instant.now().minus(1, ChronoUnit.DAYS),
                null, NotraiusStatuses.RULE_SELECTION_ACTIVE);
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(selectionRepository.findByIdAndPropertyId(current.getId(), property.getId()))
                .thenReturn(Optional.of(current));
        when(revisionService.revisionsOf(Entities.PROPERTY_RULES, current.getId(), 1))
                .thenReturn(List.of(new RevisionSummaryResponse(1, Instant.now(), true)));

        assertThat(service.listRuleSelectionRevisions(property.getId(), current.getId(), OWNER))
                .hasSize(1);
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private PropertyPolicyValue value(Pricing pricing, Policy policy, BigDecimal number) {
        PropertyPolicyValue value = new PropertyPolicyValue();
        value.setId(UUID.randomUUID());
        value.setPricing(pricing);
        value.setPolicy(policy);
        value.setValue(number);
        value.setStatus(status(NotraiusStatuses.POLICY_VALUE_ACTIVE));
        value.setVersion(1);
        return value;
    }

    private Property property(UUID ownerId) {
        Property property = new Property();
        property.setId(UUID.randomUUID());
        property.setOwnerId(ownerId);
        property.setTitle("Villa");
        property.setStateId(STATE);
        property.setLocationId(UUID.randomUUID());
        property.setCurrencyId(UUID.randomUUID());
        property.setStatus(status(NotraiusStatuses.PROPERTY_PUBLISHED));
        property.setVersion(1);
        return property;
    }

    private Pricing pricing(UUID ownerId) {
        Pricing pricing = new Pricing();
        pricing.setId(UUID.randomUUID());
        pricing.setProperty(property(ownerId));
        pricing.setAmount(new BigDecimal("250.00"));
        pricing.setVersion(1);
        pricing.setStatus(status(NotraiusStatuses.PRICING_LIVE));
        Validity validity = openValidity();
        validity.setFromDate(Instant.now().minus(1, ChronoUnit.DAYS));
        pricing.setValidity(validity);
        return pricing;
    }

    private Policy policy(String statusCode) {
        Policy policy = new Policy();
        policy.setId(UUID.randomUUID());
        policy.setCode("ADVANCE");
        policy.setTitle("Advance");
        policy.setUnit(PolicyUnit.UNITS);
        policy.setMinimum(new BigDecimal("1"));
        policy.setMaximum(new BigDecimal("12"));
        policy.setMandatory(true);
        policy.setStateId(STATE);
        policy.setVersion(1);
        policy.setStatus(status(statusCode));
        return policy;
    }

    private PropertyPolicyValue value(Pricing pricing, Policy policy, String amount, String statusCode) {
        PropertyPolicyValue value = new PropertyPolicyValue();
        value.setId(UUID.randomUUID());
        value.setPricing(pricing);
        value.setPolicy(policy);
        value.setValue(new BigDecimal(amount));
        value.setVersion(1);
        value.setStatus(status(statusCode));
        return value;
    }

    private Rule rule(UUID creatorId, RuleVisibility visibility) {
        Rule rule = new Rule();
        rule.setId(UUID.randomUUID());
        rule.setCreatorId(creatorId);
        rule.setTitle("No pets");
        rule.setDescription("No pets on the premises");
        rule.setVisibility(visibility);
        rule.setVersion(1);
        rule.setStatus(status(NotraiusStatuses.RULE_ACTIVE));
        return rule;
    }

    private PropertyRuleSelection selection(Property property, Rule rule, Instant from, Instant to,
                                            String statusCode) {
        Validity validity = openValidity();
        validity.setFromDate(from);
        validity.setToDate(to);
        validity.setStatus(status(statusCode));

        PropertyRuleSelection selection = new PropertyRuleSelection();
        selection.setId(UUID.randomUUID());
        selection.setProperty(property);
        selection.setRule(rule);
        selection.setValidity(validity);
        selection.setVersion(1);
        return selection;
    }

    private Validity openValidity() {
        Validity validity = new Validity();
        validity.setId(UUID.randomUUID());
        validity.setStatus(status(NotraiusStatuses.RULE_SELECTION_ACTIVE));
        return validity;
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
