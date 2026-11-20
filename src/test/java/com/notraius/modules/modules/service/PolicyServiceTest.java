package com.notraius.modules.modules.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notraius.common.audit.AuditTrail;
import com.notraius.common.NotraiusErrors;
import com.notraius.common.dto.RevisionSummaryResponse;
import com.notraius.common.services.policy.PolicyValueSeeder;
import com.notraius.common.services.visit.QueryFilters;
import com.notraius.common.services.visit.RevisionService;
import com.notraius.common.services.logs.StatusResolver;
import com.notraius.database.entities.asset.config.Policy;
import com.notraius.database.entities.asset.config.PolicyUnit;
import com.notraius.database.entities.lease.reference.amenity.Entities;
import com.notraius.database.entities.lease.reference.status.Status;
import com.notraius.database.entities.lease.reference.status.Type;
import com.notraius.database.repositories.asset.config.PolicyRepository;
import com.notraius.database.repositories.lease.reference.duration.EntitiesRepository;
import com.notraius.database.repositories.lease.reference.duration.TypeRepository;
import com.notraius.modules.core.config.policies.dto.request.PolicyCreateRequest;
import com.notraius.modules.core.config.policies.dto.request.PolicyUpdateRequest;
import com.notraius.modules.core.config.policies.dto.PolicyResponse;
import com.notraius.modules.lease.asset.terms.TermsService;
import com.notraius.modules.core.config.policies.PolicyMapper;
import com.notraius.modules.core.core.common.constants.NotraiusStatuses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.notraius.common.audit.RequiredAuditWriter;
import org.primarion.module.communication.errors.exception.AppException;
import org.primarion.module.communication.errors.client.BadRequestException;
import org.primarion.module.communication.errors.client.ConflictException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.primarion.module.communication.errors.core.ErrorCode;
import com.notraius.modules.core.config.policies.PolicyService;
import com.notraius.modules.services.QueryFiltersTest;

/**
 * Stress check for the policies sub-module (PLAN.md 3.2): identity is
 * (entity, code), the type must live in the policy's namespace, bounds are
 * advisory-strict, the unit is fixed at birth, and the status is a two-position
 * switch.
 */
@ExtendWith(MockitoExtension.class)
class PolicyServiceTest {

    private static final UUID ADMIN = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock private PolicyRepository policyRepository;
    @Mock private EntitiesRepository entitiesRepository;
    @Mock private TypeRepository typeRepository;
    @Mock private StatusResolver statusResolver;
    @Mock private TermsService termsService;
    @Mock private PolicyValueSeeder policyValueSeeder;
    @Mock private RequiredAuditWriter auditLogWriter;
    @Mock private QueryFilters queryFilters;
    @Mock private RevisionService revisionService;

    private PolicyService service;

    @BeforeEach
    void setUp() {
        service = new PolicyService(policyRepository, entitiesRepository, typeRepository, statusResolver,
                termsService, policyValueSeeder, new PolicyMapper(), new AuditTrail(new ObjectMapper().findAndRegisterModules(), auditLogWriter),
                queryFilters, revisionService);
        lenient().when(statusResolver.resolve(any(), any()))
                .thenAnswer(invocation -> status(invocation.getArgument(1)));
        // Filter law is QueryFiltersTest's subject; pass-through keeps these
        // tests on the service's own decisions.
        lenient().when(queryFilters.status(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(queryFilters.entity(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(queryFilters.search(any())).thenAnswer(inv -> inv.getArgument(0));
        // The deposit matrix names each commitment by a LEASE_DEPOSITS type of the
        // same code; creation refuses a code without one, so the happy path gets
        // the type it would find on a booted database.
        lenient().when(typeRepository.findByEntityCodeAndCode(eq(Entities.LEASE_DEPOSITS), anyString()))
                .thenAnswer(inv -> Optional.of(type(inv.getArgument(1), entity(Entities.LEASE_DEPOSITS))));
    }

    @Test
    @DisplayName("a standard cannot be born before the deposit type it becomes")
    void birthRequiresItsDepositType() {
        Entities entity = entity("POLICIES");
        Type installment = type("INSTALLMENT", entity);
        when(entitiesRepository.findByCode("POLICIES")).thenReturn(Optional.of(entity));
        when(typeRepository.findById(installment.getId())).thenReturn(Optional.of(installment));
        when(typeRepository.findByEntityCodeAndCode(eq(Entities.LEASE_DEPOSITS), eq("ADVANCE")))
                .thenReturn(Optional.empty());

        // Every property in the standard's world would publish and then refuse
        // every tenant (LEASE_DEPOSIT_TYPE_MISSING), so the ordering is refused
        // at the standard's birth instead.
        assertThatThrownBy(() -> service.createPolicy(request(installment.getId(), "ADVANCE"), ADMIN))
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.POLICY_DEPOSIT_TYPE_MISSING));
        verify(policyRepository, never()).save(any());
    }

    @Test
    @DisplayName("a counting standard's bounds are whole numbers above zero")
    void countingBoundsMustBeWholeAndPositive() {
        Entities entity = entity("POLICIES");
        Type installment = type("INSTALLMENT", entity);
        when(entitiesRepository.findByCode("POLICIES")).thenReturn(Optional.of(entity));
        when(typeRepository.findById(installment.getId())).thenReturn(Optional.of(installment));

        PolicyCreateRequest fractional = new PolicyCreateRequest("POLICIES", installment.getId(), "ADVANCE",
                "Advance", null, new BigDecimal("1.5"), new BigDecimal("12"), false, "UNITS", null);
        assertThatThrownBy(() -> service.createPolicy(fractional, ADMIN))
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.POLICY_BOUNDS_INVALID));

        PolicyCreateRequest zero = new PolicyCreateRequest("POLICIES", installment.getId(), "ADVANCE",
                "Advance", null, BigDecimal.ZERO, new BigDecimal("12"), false, "UNITS", null);
        assertThatThrownBy(() -> service.createPolicy(zero, ADMIN))
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.POLICY_BOUNDS_INVALID));
        verify(policyRepository, never()).save(any());
    }

    @Test
    @DisplayName("birth is ENABLED at version one, and the first history entry")
    void birthIsEnabledAndVersionOne() {
        Entities entity = entity("POLICIES");
        Type installment = type("INSTALLMENT", entity);
        when(entitiesRepository.findByCode("POLICIES")).thenReturn(Optional.of(entity));
        when(typeRepository.findById(installment.getId())).thenReturn(Optional.of(installment));

        PolicyResponse created = service.createPolicy(request(installment.getId(), "ADVANCE"), ADMIN);

        assertThat(created.getStatusCode()).isEqualTo(NotraiusStatuses.POLICY_ENABLED);
        assertThat(created.getVersion()).isEqualTo(1);
        assertThat(created.getUnit()).isEqualTo("UNITS");
        assertThat(created.getCode()).isEqualTo("ADVANCE");
        verify(auditLogWriter).writeRequired(eq(Entities.POLICIES), eq("POLICY.CREATE"), any(), eq("CREATE"),
                any(), any());
    }

    @Test
    @DisplayName("the type must belong to the entity that namespaces the policy")
    void typeFromAnotherNamespaceIsRefused() {
        Entities policies = entity("POLICIES");
        Type propertyKind = type("APARTMENT", entity("PROPERTY"));
        when(entitiesRepository.findByCode("POLICIES")).thenReturn(Optional.of(policies));
        when(typeRepository.findById(propertyKind.getId())).thenReturn(Optional.of(propertyKind));

        assertThatThrownBy(() -> service.createPolicy(request(propertyKind.getId(), "ADVANCE"), ADMIN))
                .isInstanceOf(BadRequestException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.POLICY_TYPE_ENTITY_MISMATCH));
    }

    @Test
    @DisplayName("a code is unique inside its entity — the standard has one identity")
    void duplicateCodeIsRefused() {
        Entities entity = entity("POLICIES");
        Type installment = type("INSTALLMENT", entity);
        when(entitiesRepository.findByCode("POLICIES")).thenReturn(Optional.of(entity));
        when(typeRepository.findById(installment.getId())).thenReturn(Optional.of(installment));
        when(policyRepository.existsByEntityIdAndCodeAndStateIdIsNull(entity.getId(), "ADVANCE"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.createPolicy(request(installment.getId(), "ADVANCE"), ADMIN))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.POLICY_CODE_DUPLICATE));
        verify(policyRepository, never()).save(any());
    }

    @Test
    @DisplayName("bounds are advisory-strict: a minimum above the maximum is refused")
    void invertedBoundsAreRefused() {
        Entities entity = entity("POLICIES");
        Type installment = type("INSTALLMENT", entity);
        when(entitiesRepository.findByCode("POLICIES")).thenReturn(Optional.of(entity));
        when(typeRepository.findById(installment.getId())).thenReturn(Optional.of(installment));

        PolicyCreateRequest request = request(installment.getId(), "ADVANCE");
        request.setMin(new BigDecimal("10.00"));
        request.setMax(new BigDecimal("5.00"));

        assertThatThrownBy(() -> service.createPolicy(request, ADMIN))
                .isInstanceOf(BadRequestException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.POLICY_BOUNDS_INVALID));
    }

    @Test
    @DisplayName("the unit has exactly two readings — a third is refused")
    void unknownUnitIsRefused() {
        Entities entity = entity("POLICIES");
        Type installment = type("INSTALLMENT", entity);
        when(entitiesRepository.findByCode("POLICIES")).thenReturn(Optional.of(entity));
        when(typeRepository.findById(installment.getId())).thenReturn(Optional.of(installment));

        PolicyCreateRequest request = request(installment.getId(), "ADVANCE");
        request.setUnit("PERCENTAGE");

        assertThatThrownBy(() -> service.createPolicy(request, ADMIN))
                .isInstanceOf(BadRequestException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.POLICY_UNIT_INVALID));
    }

    @Test
    @DisplayName("an accepted edit bumps the version and records before/after")
    void editsBumpTheVersion() {
        Policy policy = policy(NotraiusStatuses.POLICY_ENABLED);
        when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));

        PolicyResponse edited = service.updatePolicy(policy.getId(),
                new PolicyUpdateRequest("Advance on installments", null, null, new BigDecimal("3.00")));

        assertThat(edited.getVersion()).isEqualTo(2);
        assertThat(edited.getMax()).isEqualByComparingTo("3.00");
        verify(auditLogWriter).writeRequired(eq(Entities.POLICIES), eq("POLICY.UPDATE"), eq(policy.getId()),
                eq("UPDATE"), any(), any());
    }

    @Test
    @DisplayName("tightening the bounds cannot invert them either")
    void tightenedBoundsAreStillChecked() {
        Policy policy = policy(NotraiusStatuses.POLICY_ENABLED);
        policy.setMinimum(new BigDecimal("2.00"));
        policy.setMaximum(new BigDecimal("5.00"));
        when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));

        assertThatThrownBy(() -> service.updatePolicy(policy.getId(),
                new PolicyUpdateRequest(null, null, new BigDecimal("9.00"), null)))
                .isInstanceOf(BadRequestException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.POLICY_BOUNDS_INVALID));
    }

    @Test
    @DisplayName("the mandatory flag is its own act, versioned like any other")
    void mandatoryFlipIsVersioned() {
        Policy policy = policy(NotraiusStatuses.POLICY_ENABLED);
        when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));

        PolicyResponse flipped = service.setMandatory(policy.getId(), true);

        assertThat(flipped.isMandatory()).isTrue();
        assertThat(flipped.getVersion()).isEqualTo(2);
        verify(auditLogWriter).writeRequired(eq(Entities.POLICIES), eq("POLICY.MANDATORY"), eq(policy.getId()),
                eq("MANDATORY"), any(), any());
    }

    @Test
    @DisplayName("disable is the pause button, and enable restores enforcement")
    void disableAndEnableRoundTrip() {
        Policy policy = policy(NotraiusStatuses.POLICY_ENABLED);
        when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));

        assertThat(service.disablePolicy(policy.getId()).getStatusCode())
                .isEqualTo(NotraiusStatuses.POLICY_DISABLED);
        assertThat(service.enablePolicy(policy.getId()).getStatusCode())
                .isEqualTo(NotraiusStatuses.POLICY_ENABLED);
    }

    @Test
    @DisplayName("only an ENABLED policy can be paused")
    void disableRequiresEnabled() {
        Policy policy = policy(NotraiusStatuses.POLICY_DISABLED);
        when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));

        assertThatThrownBy(() -> service.disablePolicy(policy.getId()))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.POLICY_INVALID_STATE));
    }

    @Test
    @DisplayName("the pause is a revision too — a standard's status is part of its truth")
    void disableAndEnableBumpTheVersion() {
        Policy policy = policy(NotraiusStatuses.POLICY_ENABLED);
        when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));

        assertThat(service.disablePolicy(policy.getId()).getVersion()).isEqualTo(2);
        assertThat(service.enablePolicy(policy.getId()).getVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("an edit's history entry states the PREVIOUS bounds on its old side")
    void editSnapshotRecordsTheOldTruth() {
        Policy policy = policy(NotraiusStatuses.POLICY_ENABLED);
        policy.setMinimum(new BigDecimal("1.00"));
        policy.setMaximum(new BigDecimal("12.00"));
        when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));

        service.updatePolicy(policy.getId(),
                new PolicyUpdateRequest(null, null, null, new BigDecimal("3.00")));

        org.mockito.ArgumentCaptor<com.fasterxml.jackson.databind.JsonNode> oldSide =
                org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        org.mockito.ArgumentCaptor<com.fasterxml.jackson.databind.JsonNode> newSide =
                org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(auditLogWriter).writeRequired(eq(Entities.POLICIES), eq("POLICY.UPDATE"), eq(policy.getId()),
                eq("UPDATE"), oldSide.capture(), newSide.capture());

        assertThat(oldSide.getValue().get("max").decimalValue()).isEqualByComparingTo("12.00");
        assertThat(oldSide.getValue().get("version").asInt()).isEqualTo(1);
        assertThat(newSide.getValue().get("max").decimalValue()).isEqualByComparingTo("3.00");
    }

    @Test
    @DisplayName("disabling pauses the values already set against the standard")
    void disablePausesValues() {
        Policy policy = policy(NotraiusStatuses.POLICY_ENABLED);
        when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));

        service.disablePolicy(policy.getId());

        verify(termsService).pauseValuesOfPolicy(policy.getId());
    }

    @Test
    @DisplayName("re-enabling re-evaluates stored values and flags the out-of-range ones")
    void enableFlagsValues() {
        Policy policy = policy(NotraiusStatuses.POLICY_DISABLED);
        when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));
        when(termsService.resumeValuesOfPolicy(policy)).thenReturn(2);

        assertThat(service.enablePolicy(policy.getId()).getStatusCode())
                .isEqualTo(NotraiusStatuses.POLICY_ENABLED);
        verify(termsService).resumeValuesOfPolicy(policy);
    }

    @Test
    @DisplayName("making a standard mandatory backfills every pricing in its world")
    void mandatoryBackfillsValues() {
        Policy policy = policy(NotraiusStatuses.POLICY_ENABLED);
        when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));
        when(policyValueSeeder.backfillMandatory(policy, null)).thenReturn(4);

        service.setMandatory(policy.getId(), true);

        verify(policyValueSeeder).backfillMandatory(policy, null);
    }

    @Test
    @DisplayName("a policy has no delete path — values set against it are property history")
    void disablingNeverDeletes() {
        Policy policy = policy(NotraiusStatuses.POLICY_ENABLED);
        when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));

        service.disablePolicy(policy.getId());

        verify(policyRepository, never()).delete(any());
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("a standard's revision list is public with the standard — versions only")
    void revisionsAreListedForAnyone() {
        Policy policy = policy(NotraiusStatuses.POLICY_ENABLED);
        policy.setVersion(2);
        when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));
        when(revisionService.revisionsOf(Entities.POLICIES, policy.getId(), 2)).thenReturn(List.of(
                new RevisionSummaryResponse(2, Instant.now(), true)));

        assertThat(service.listRevisions(policy.getId()))
                .singleElement()
                .extracting(RevisionSummaryResponse::getVersion)
                .isEqualTo(2);
    }

    private Policy policy(String statusCode) {
        Policy policy = new Policy();
        policy.setId(UUID.randomUUID());
        policy.setEntity(entity("POLICIES"));
        policy.setType(type("INSTALLMENT", policy.getEntity()));
        policy.setCode("ADVANCE");
        policy.setTitle("Advance");
        policy.setUnit(PolicyUnit.UNITS);
        policy.setVersion(1);
        policy.setStatus(status(statusCode));
        return policy;
    }

    private PolicyCreateRequest request(UUID typeId, String code) {
        return new PolicyCreateRequest("POLICIES", typeId, code, "Advance", "Prepaid advance", null, null,
                false, "UNITS", null);
    }

    private Entities entity(String code) {
        Entities entity = new Entities();
        entity.setId(UUID.randomUUID());
        entity.setCode(code);
        return entity;
    }

    private Type type(String code, Entities entity) {
        Type type = new Type();
        type.setId(UUID.randomUUID());
        type.setCode(code);
        type.setLabel(code);
        type.setEntity(entity);
        type.setStatus(status("ACTIVE"));
        return type;
    }

    private Status status(String code) {
        Status status = new Status();
        status.setId(UUID.randomUUID());
        status.setCode(code);
        return status;
    }

    private static org.primarion.module.communication.errors.core.ErrorCode errorOf(Throwable thrown) {
        assertThat(thrown).isInstanceOf(AppException.class);
        return ((AppException) thrown).getError();
    }
}
