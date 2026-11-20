package com.notraius.modules.modules.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notraius.common.audit.AuditTrail;
import com.notraius.common.NotraiusErrors;
import com.notraius.common.services.visit.QueryFilters;
import com.notraius.common.services.logs.StatusResolver;
import com.notraius.database.repositories.asset.asset.PricingRepository;
import com.notraius.database.repositories.asset.asset.PropertyRepository;
import com.notraius.modules.lease.asset.property.PropertyService;
import com.notraius.database.entities.lease.reference.amenity.Amenity;
import com.notraius.database.entities.lease.reference.amenity.AmenityDataType;
import com.notraius.database.entities.lease.reference.status.Duration;
import com.notraius.database.entities.lease.reference.amenity.Entities;
import com.notraius.database.entities.lease.reference.status.Status;
import com.notraius.database.entities.lease.reference.status.Type;
import com.notraius.database.repositories.asset.config.PolicyRepository;
import com.notraius.database.repositories.lease.reference.duration.AmenityRepository;
import com.notraius.database.repositories.lease.reference.duration.DurationRepository;
import com.notraius.database.repositories.lease.reference.duration.EntitiesRepository;
import com.notraius.database.repositories.lease.reference.duration.TypeRepository;
import com.notraius.modules.core.config.reference.ReferenceMapper;
import com.notraius.modules.core.config.reference.dto.request.amenity.AmenityCreateRequest;
import com.notraius.modules.core.config.reference.dto.request.duration.DurationCreateRequest;
import com.notraius.modules.core.config.reference.dto.request.duration.DurationUpdateRequest;
import com.notraius.modules.core.config.reference.dto.request.type.TypeCreateRequest;
import com.notraius.modules.core.config.reference.dto.request.type.TypeReparentRequest;
import com.notraius.modules.core.config.reference.dto.request.type.TypeUpdateRequest;
import com.notraius.modules.core.config.reference.dto.response.AmenityResponse;
import com.notraius.modules.core.config.reference.dto.response.DurationResponse;
import com.notraius.modules.core.config.reference.dto.response.TypeResponse;
import com.notraius.modules.core.core.common.constants.NotraiusStatuses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.primarion.module.app.access.consent.AuditLogWriter;
import org.primarion.module.communication.errors.exception.AppException;
import org.primarion.module.communication.errors.client.BadRequestException;
import org.primarion.module.communication.errors.client.ConflictException;
import org.primarion.module.communication.errors.client.ForbiddenException;
import org.primarion.module.utils.utils.security.security.SecurityAuthorityUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.primarion.module.communication.errors.core.ErrorCode;
import com.notraius.modules.core.config.reference.ReferenceService;
import com.notraius.modules.services.QueryFiltersTest;
import java.util.Locale;

/**
 * Stress check for the reference sub-module: one test per law PLAN.md states
 * for types (Part 1), durations (Part 2) and amenities (Part 3.3).
 * <p>
 * Pure unit tests over mocked repositories — the laws live in the service, so
 * this is where they are provable without a database. The database-level
 * backstops (unique (entity, code), unique (type, value), the positive-value
 * CHECK, the no-self-parent CHECK) are asserted by the migration itself.
 * <p>
 * No security context is installed: {@code SecurityAuthorityUtils.currentIsStaff()}
 * therefore answers false, which is exactly the personal-tier caller the laws
 * are mostly about; the one staff-birth test mocks it.
 */
@ExtendWith(MockitoExtension.class)
class ReferenceServiceTest {

    private static final UUID CALLER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private TypeRepository typeRepository;
    @Mock private PolicyRepository policyRepository;
    @Mock private DurationRepository durationRepository;
    @Mock private AmenityRepository amenityRepository;
    @Mock private PropertyRepository propertyRepository;
    @Mock private PricingRepository pricingRepository;
    @Mock private PropertyService propertyService;
    @Mock private EntitiesRepository entitiesRepository;
    @Mock private StatusResolver statusResolver;
    @Mock private AuditLogWriter auditLogWriter;
    @Mock private QueryFilters queryFilters;

    private ReferenceService service;

    @BeforeEach
    void setUp() {
        service = new ReferenceService(typeRepository, policyRepository, durationRepository, amenityRepository,
                propertyRepository, pricingRepository, propertyService, entitiesRepository, statusResolver,
                new ReferenceMapper(), new AuditTrail(new ObjectMapper().findAndRegisterModules(), auditLogWriter), queryFilters);
        lenient().when(statusResolver.resolve(any(), any()))
                .thenAnswer(invocation -> status(invocation.getArgument(1)));
        // Filter law is QueryFiltersTest's subject; pass-through keeps these
        // tests on the catalogue's own decisions.
        // Filter law is QueryFiltersTest's subject; here the canonicalisation the
        // service relies on must still happen, so the passthrough upper-cases the
        // way QueryFilters does.
        lenient().when(queryFilters.status(any(), any())).thenAnswer(inv -> upper(inv.getArgument(1)));
        lenient().when(queryFilters.entity(any())).thenAnswer(inv -> upper(inv.getArgument(0)));
        lenient().when(queryFilters.search(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TYPES — Part 1
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Types")
    class Types {

        @Test
        @DisplayName("a code is unique inside its entity — the same code twice is the same type")
        void duplicateCodeIsRefused() {
            Entities property = entity("PROPERTY");
            when(entitiesRepository.findByCode("PROPERTY")).thenReturn(Optional.of(property));
            when(typeRepository.existsByEntityIdAndCode(property.getId(), "ROOM")).thenReturn(true);

            assertThatThrownBy(() -> service.createType(typeRequest("PROPERTY", "room", null), CALLER))
                    .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.TYPE_CODE_DUPLICATE));
            verify(typeRepository, never()).save(any());
        }

        @Test
        @DisplayName("the hierarchy never crosses an entity — the entity is the namespace")
        void parentFromAnotherEntityIsRefused() {
            Entities property = entity("PROPERTY");
            Entities pricings = entity("PRICINGS");
            Type monthly = type("MONTHLY", pricings, NotraiusStatuses.TYPE_ACTIVE);

            when(entitiesRepository.findByCode("PROPERTY")).thenReturn(Optional.of(property));
            when(typeRepository.findById(monthly.getId())).thenReturn(Optional.of(monthly));

            assertThatThrownBy(() -> service.createType(typeRequest("PROPERTY", "ROOM", monthly.getId()), CALLER))
                    .isInstanceOf(BadRequestException.class)
                    .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.TYPE_PARENT_ENTITY_MISMATCH));
        }

        @Test
        @DisplayName("birth normalises the code, stamps ACTIVE and audits with before/after data")
        void birthIsCanonicalAndAudited() {
            Entities property = entity("PROPERTY");
            when(entitiesRepository.findByCode("PROPERTY")).thenReturn(Optional.of(property));

            TypeResponse created = service.createType(typeRequest("PROPERTY", " room ", null), CALLER);

            assertThat(created.getCode()).isEqualTo("ROOM");
            assertThat(created.getStatusCode()).isEqualTo(NotraiusStatuses.TYPE_ACTIVE);
            assertThat(created.getEntityCode()).isEqualTo("PROPERTY");
            assertThat(created.getCreatedBy()).isEqualTo(CALLER);
            verify(auditLogWriter).write(eq(Entities.TYPES), eq("TYPE.CREATE"), any(), eq("CREATE"),
                    isNull(), any());
        }

        @Test
        @DisplayName("a reparent beneath the type's own descendant is refused — a cycle is unresolvable")
        void reparentCycleIsRefused() {
            Entities property = entity("PROPERTY");
            Type root = type("ROOT", property, NotraiusStatuses.TYPE_ACTIVE);
            Type child = type("CHILD", property, NotraiusStatuses.TYPE_ACTIVE);
            child.setParent(root);

            when(typeRepository.findById(root.getId())).thenReturn(Optional.of(root));
            when(typeRepository.findById(child.getId())).thenReturn(Optional.of(child));

            assertThatThrownBy(() -> service.reparentType(root.getId(), new TypeReparentRequest(child.getId())))
                    .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.TYPE_PARENT_CYCLE));
        }

        @Test
        @DisplayName("the subtree stops growing at a retired parent")
        void retiredParentMayNotTakeNewChildren() {
            Entities property = entity("PROPERTY");
            Type retired = type("OLD", property, NotraiusStatuses.TYPE_RETIRED);
            when(entitiesRepository.findByCode("PROPERTY")).thenReturn(Optional.of(property));
            when(typeRepository.findById(retired.getId())).thenReturn(Optional.of(retired));

            assertThatThrownBy(() -> service.createType(typeRequest("PROPERTY", "ROOM", retired.getId()), CALLER))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.TYPE_RETIRED));
        }

        @Test
        @DisplayName("a type cannot be reparented beneath itself")
        void reparentSelfIsRefused() {
            Entities property = entity("PROPERTY");
            Type root = type("ROOT", property, NotraiusStatuses.TYPE_ACTIVE);
            when(typeRepository.findById(root.getId())).thenReturn(Optional.of(root));

            assertThatThrownBy(() -> service.reparentType(root.getId(), new TypeReparentRequest(root.getId())))
                    .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.TYPE_PARENT_CYCLE));
        }

        @Test
        @DisplayName("the parent is replaced outright — a null parent promotes the node to a root")
        void reparentToRootClearsTheParent() {
            Entities property = entity("PROPERTY");
            Type root = type("ROOT", property, NotraiusStatuses.TYPE_ACTIVE);
            Type child = type("CHILD", property, NotraiusStatuses.TYPE_ACTIVE);
            child.setParent(root);
            when(typeRepository.findById(child.getId())).thenReturn(Optional.of(child));

            TypeResponse moved = service.reparentType(child.getId(), new TypeReparentRequest(null));

            assertThat(moved.getParentId()).isNull();
            verify(typeRepository).save(child);
        }

        @Test
        @DisplayName("a type in use is retired, never deleted")
        void deleteWithChildrenIsRefused() {
            Type root = type("ROOT", entity("PROPERTY"), NotraiusStatuses.TYPE_ACTIVE);
            when(typeRepository.findById(root.getId())).thenReturn(Optional.of(root));
            when(typeRepository.existsByParentId(root.getId())).thenReturn(true);

            assertThatThrownBy(() -> service.deleteType(root.getId()))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.TYPE_IN_USE));
            verify(typeRepository, never()).delete(any());
        }

        @Test
        @DisplayName("a type is in use when a duration binds to it")
        void deleteWithDurationsIsRefused() {
            Type unit = type("HOUR", entity("DURATION_UNIT"), NotraiusStatuses.TYPE_ACTIVE);
            when(typeRepository.findById(unit.getId())).thenReturn(Optional.of(unit));
            when(durationRepository.existsByTypeId(unit.getId())).thenReturn(true);

            assertThatThrownBy(() -> service.deleteType(unit.getId()))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.TYPE_IN_USE));
        }

        @Test
        @DisplayName("a retired type is read-only history")
        void retiredTypeCannotBeEdited() {
            Type retired = type("OLD", entity("PROPERTY"), NotraiusStatuses.TYPE_RETIRED);
            when(typeRepository.findById(retired.getId())).thenReturn(Optional.of(retired));

            assertThatThrownBy(() -> service.updateType(retired.getId(), new TypeUpdateRequest("New", null)))
                    .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.TYPE_RETIRED));
        }

        @Test
        @DisplayName("retire is idempotent and never touches the children")
        void retireIsIdempotent() {
            Type retired = type("OLD", entity("PROPERTY"), NotraiusStatuses.TYPE_RETIRED);
            when(typeRepository.findById(retired.getId())).thenReturn(Optional.of(retired));

            TypeResponse again = service.retireType(retired.getId());

            assertThat(again.getStatusCode()).isEqualTo(NotraiusStatuses.TYPE_RETIRED);
            verify(typeRepository, never()).save(any());
        }

        @Test
        @DisplayName("a listing defaults to ACTIVE rows only, so no dropdown offers a retired kind")
        void listingDefaultsToActive() {
            when(typeRepository.search(eq("PROPERTY"), isNull(), eq(NotraiusStatuses.TYPE_ACTIVE),
                    isNull(), any())).thenReturn(org.springframework.data.domain.Page.<Type>empty());

            service.listTypes("property", null, null, null, org.springframework.data.domain.Pageable.unpaged());

            verify(typeRepository).search("PROPERTY", null, NotraiusStatuses.TYPE_ACTIVE, null,
                    org.springframework.data.domain.Pageable.unpaged());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DURATIONS — Part 2
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Durations")
    class Durations {

        @Test
        @DisplayName("a night of zero hours prices nothing — the value must be at least one")
        void zeroValueIsRefused() {
            Type hour = type("HOUR", entity("DURATION_UNIT"), NotraiusStatuses.TYPE_ACTIVE);
            when(typeRepository.findById(hour.getId())).thenReturn(Optional.of(hour));

            assertThatThrownBy(() -> service.createDuration(durationRequest(hour.getId(), 0), CALLER))
                    .isInstanceOf(BadRequestException.class)
                    .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.DURATION_VALUE_INVALID));
        }

        @Test
        @DisplayName("(type, value) is the identity — HOUR + 10 created twice is one row")
        void duplicatePairIsRefused() {
            Type hour = type("HOUR", entity("DURATION_UNIT"), NotraiusStatuses.TYPE_ACTIVE);
            when(typeRepository.findById(hour.getId())).thenReturn(Optional.of(hour));
            when(durationRepository.existsByTypeIdAndValue(hour.getId(), 10)).thenReturn(true);

            assertThatThrownBy(() -> service.createDuration(durationRequest(hour.getId(), 10), CALLER))
                    .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.DURATION_DUPLICATE));
            verify(durationRepository, never()).save(any());
        }

        @Test
        @DisplayName("a duration may only bind to an ACTIVE type")
        void retiredTypeIsRefused() {
            Type week = type("WEEK", entity("DURATION_UNIT"), NotraiusStatuses.TYPE_RETIRED);
            when(typeRepository.findById(week.getId())).thenReturn(Optional.of(week));

            assertThatThrownBy(() -> service.createDuration(durationRequest(week.getId(), 2), CALLER))
                    .isInstanceOf(BadRequestException.class)
                    .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.DURATION_TYPE_NOT_LIVE));
        }

        @Test
        @DisplayName("a user mints their own duration and it is ACTIVE, attributed and audited")
        void userCreationIsLiveAndAttributed() {
            Type week = type("WEEK", entity("DURATION_UNIT"), NotraiusStatuses.TYPE_ACTIVE);
            when(typeRepository.findById(week.getId())).thenReturn(Optional.of(week));

            DurationResponse created = service.createDuration(durationRequest(week.getId(), 2), CALLER);

            assertThat(created.getTypeCode()).isEqualTo("WEEK");
            assertThat(created.getValue()).isEqualTo(2);
            assertThat(created.getStatusCode()).isEqualTo(NotraiusStatuses.DURATION_ACTIVE);
            assertThat(created.getCreatedBy()).isEqualTo(CALLER);
            verify(auditLogWriter).write(eq(Entities.DURATIONS), eq("DURATION.CREATE"), any(),
                    eq("CREATE"), isNull(), any());
        }

        @Test
        @DisplayName("a retired duration cannot be edited")
        void retiredDurationCannotBeEdited() {
            Duration retired = duration(10, NotraiusStatuses.DURATION_RETIRED);
            when(durationRepository.findById(retired.getId())).thenReturn(Optional.of(retired));

            assertThatThrownBy(() -> service.updateDuration(retired.getId(), new DurationUpdateRequest(12)))
                    .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.DURATION_RETIRED));
        }

        @Test
        @DisplayName("editing onto an existing (type, value) pair is refused")
        void editOntoExistingPairIsRefused() {
            Duration ten = duration(10, NotraiusStatuses.DURATION_ACTIVE);
            when(durationRepository.findById(ten.getId())).thenReturn(Optional.of(ten));
            when(durationRepository.existsByTypeIdAndValueAndIdNot(
                    ten.getType().getId(), 12, ten.getId())).thenReturn(true);

            assertThatThrownBy(() -> service.updateDuration(ten.getId(), new DurationUpdateRequest(12)))
                    .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.DURATION_DUPLICATE));
        }

        @Test
        @DisplayName("re-saving the same value is a no-op, not a duplicate")
        void unchangedValueIsANoOp() {
            Duration ten = duration(10, NotraiusStatuses.DURATION_ACTIVE);
            when(durationRepository.findById(ten.getId())).thenReturn(Optional.of(ten));

            service.updateDuration(ten.getId(), new DurationUpdateRequest(10));

            verify(durationRepository, never()).save(any());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AMENITIES — Part 3.3
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Amenities")
    class Amenities {

        @Test
        @DisplayName("a personal user's submission lands in PENDING and belongs to them")
        void userProposalIsPendingAndOwned() {
            Type comfort = type("COMFORT", entity("AMENITIES"), NotraiusStatuses.TYPE_ACTIVE);
            when(typeRepository.findById(comfort.getId())).thenReturn(Optional.of(comfort));

            AmenityResponse proposed = service.createAmenity(amenityRequest(comfort.getId(), "POOL"), CALLER);

            assertThat(proposed.getStatusCode()).isEqualTo(NotraiusStatuses.AMENITY_PENDING);
            assertThat(proposed.getProposerId()).isEqualTo(CALLER);
            assertThat(proposed.getDataType()).isEqualTo("BOOLEAN");
        }

        @Test
        @DisplayName("an admin's own submission is ACTIVE at birth — staff birth is staff approval")
        void staffProposalIsLiveAtBirth() {
            Type comfort = type("COMFORT", entity("AMENITIES"), NotraiusStatuses.TYPE_ACTIVE);
            when(typeRepository.findById(comfort.getId())).thenReturn(Optional.of(comfort));

            try (MockedStatic<SecurityAuthorityUtils> authorities =
                         Mockito.mockStatic(SecurityAuthorityUtils.class)) {
                authorities.when(SecurityAuthorityUtils::currentIsStaff).thenReturn(true);

                AmenityResponse created = service.createAmenity(amenityRequest(comfort.getId(), "POOL"), CALLER);

                assertThat(created.getStatusCode()).isEqualTo(NotraiusStatuses.AMENITY_ACTIVE);
                assertThat(created.getProposerId()).isNull();
                assertThat(created.getCreatedBy()).isEqualTo(CALLER);
            }
        }

        @Test
        @DisplayName("proposals are allowed to collide — two 'Pool' proposals both live in PENDING")
        void proposalsMayCollide() {
            Type comfort = type("COMFORT", entity("AMENITIES"), NotraiusStatuses.TYPE_ACTIVE);
            when(typeRepository.findById(comfort.getId())).thenReturn(Optional.of(comfort));

            service.createAmenity(amenityRequest(comfort.getId(), "POOL"), CALLER);

            verify(amenityRepository, never()).countLiveByCode(any(), any(), any());
        }

        @Test
        @DisplayName("confirming one duplicate is refused with a pointer to the live row")
        void confirmRefusesADuplicateOfTheLiveCatalogue() {
            Amenity pending = amenity("POOL", NotraiusStatuses.AMENITY_PENDING, OTHER);
            when(amenityRepository.findByIdForUpdate(pending.getId())).thenReturn(Optional.of(pending));
            when(amenityRepository.countLiveByCode(eq("POOL"), any(), eq(pending.getId()))).thenReturn(1L);

            assertThatThrownBy(() -> service.confirmAmenity(pending.getId()))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.AMENITY_CODE_DUPLICATE));
            verify(amenityRepository, never()).save(any());
        }

        @Test
        @DisplayName("only a PENDING proposal can be confirmed — first writer wins")
        void confirmRequiresPending() {
            Amenity rejected = amenity("POOL", NotraiusStatuses.AMENITY_REJECTED, OTHER);
            when(amenityRepository.findByIdForUpdate(rejected.getId())).thenReturn(Optional.of(rejected));

            assertThatThrownBy(() -> service.confirmAmenity(rejected.getId()))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.AMENITY_INVALID_STATE));
        }

        @Test
        @DisplayName("the proposer may withdraw a pending proposal")
        void proposerMayWithdraw() {
            Amenity pending = amenity("POOL", NotraiusStatuses.AMENITY_PENDING, CALLER);
            when(amenityRepository.findByIdForUpdate(pending.getId())).thenReturn(Optional.of(pending));

            AmenityResponse cancelled = service.cancelAmenity(pending.getId(), CALLER);

            assertThat(cancelled.getStatusCode()).isEqualTo(NotraiusStatuses.AMENITY_CANCELLED);
            verify(auditLogWriter).write(eq(Entities.AMENITIES), eq("AMENITY.CANCEL"), any(),
                    eq("CANCEL"), any(), any());
        }

        @Test
        @DisplayName("nobody withdraws somebody else's proposal — that is what reject is for")
        void nonProposerCannotWithdraw() {
            Amenity pending = amenity("POOL", NotraiusStatuses.AMENITY_PENDING, OTHER);
            when(amenityRepository.findByIdForUpdate(pending.getId())).thenReturn(Optional.of(pending));

            assertThatThrownBy(() -> service.cancelAmenity(pending.getId(), CALLER))
                    .isInstanceOf(ForbiddenException.class);
            verify(amenityRepository, never()).save(any());
        }

        @Test
        @DisplayName("a decided proposal can no longer be withdrawn")
        void decidedProposalCannotBeWithdrawn() {
            Amenity active = amenity("POOL", NotraiusStatuses.AMENITY_ACTIVE, CALLER);
            when(amenityRepository.findByIdForUpdate(active.getId())).thenReturn(Optional.of(active));

            assertThatThrownBy(() -> service.cancelAmenity(active.getId(), CALLER))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.AMENITY_INVALID_STATE));
        }

        @Test
        @DisplayName("re-enabling is a catalogue act, so the live code is re-checked")
        void enableRefusesACodeTakenWhileDisabled() {
            Amenity disabled = amenity("POOL", NotraiusStatuses.AMENITY_DISABLED, null);
            when(amenityRepository.findByIdForUpdate(disabled.getId())).thenReturn(Optional.of(disabled));
            when(amenityRepository.countLiveByCode(eq("POOL"), any(), eq(disabled.getId()))).thenReturn(1L);

            assertThatThrownBy(() -> service.enableAmenity(disabled.getId()))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.AMENITY_CODE_DUPLICATE));
        }

        @Test
        @DisplayName("disabling the catalogue suppresses the holdings it had kept visible")
        void disableSuppressesHoldings() {
            Amenity active = amenity("POOL", NotraiusStatuses.AMENITY_ACTIVE, null);
            when(amenityRepository.findByIdForUpdate(active.getId())).thenReturn(Optional.of(active));
            when(propertyService.suppressAmenityHoldings(active.getId())).thenReturn(3);

            assertThat(service.disableAmenity(active.getId()).getStatusCode())
                    .isEqualTo(NotraiusStatuses.AMENITY_DISABLED);
            verify(propertyService).suppressAmenityHoldings(active.getId());
        }

        @Test
        @DisplayName("re-enabling restores exactly the catalogue-suppressed holdings")
        void enableRestoresHoldings() {
            Amenity disabled = amenity("POOL", NotraiusStatuses.AMENITY_DISABLED, null);
            when(amenityRepository.findByIdForUpdate(disabled.getId())).thenReturn(Optional.of(disabled));
            when(amenityRepository.countLiveByCode(eq("POOL"), any(), eq(disabled.getId()))).thenReturn(0L);
            when(propertyService.restoreAmenityHoldings(disabled.getId())).thenReturn(2);

            assertThat(service.enableAmenity(disabled.getId()).getStatusCode())
                    .isEqualTo(NotraiusStatuses.AMENITY_ACTIVE);
            verify(propertyService).restoreAmenityHoldings(disabled.getId());
        }

        @Test
        @DisplayName("a held amenity cannot be retired — its references must keep resolving")
        void heldAmenityCannotBeRetired() {
            Amenity active = amenity("POOL", NotraiusStatuses.AMENITY_ACTIVE, null);
            when(amenityRepository.findByIdForUpdate(active.getId())).thenReturn(Optional.of(active));
            when(propertyService.isAmenityHeld(active.getId())).thenReturn(true);

            assertThatThrownBy(() -> service.retireAmenity(active.getId()))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.AMENITY_IN_USE));
        }

        @Test
        @DisplayName("disable is reversible and only from ACTIVE")
        void disableRequiresActive() {
            Amenity pending = amenity("POOL", NotraiusStatuses.AMENITY_PENDING, OTHER);
            when(amenityRepository.findByIdForUpdate(pending.getId())).thenReturn(Optional.of(pending));

            assertThatThrownBy(() -> service.disableAmenity(pending.getId()))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.AMENITY_INVALID_STATE));
        }

        @Test
        @DisplayName("the data type is fixed at birth — an unknown one is refused outright")
        void unknownDataTypeIsRefused() {
            Type comfort = type("COMFORT", entity("AMENITIES"), NotraiusStatuses.TYPE_ACTIVE);
            when(typeRepository.findById(comfort.getId())).thenReturn(Optional.of(comfort));
            AmenityCreateRequest request = amenityRequest(comfort.getId(), "POOL");
            request.setDataType("DECIMAL");

            assertThatThrownBy(() -> service.createAmenity(request, CALLER))
                    .isInstanceOf(BadRequestException.class)
                    .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.AMENITY_DATA_TYPE_INVALID));
        }

        @Test
        @DisplayName("retiring is the rare, checked exit — a retired amenity stays retired")
        void retireIsIdempotent() {
            Amenity retired = amenity("POOL", NotraiusStatuses.AMENITY_RETIRED, null);
            when(amenityRepository.findByIdForUpdate(retired.getId())).thenReturn(Optional.of(retired));

            AmenityResponse again = service.retireAmenity(retired.getId());

            assertThat(again.getStatusCode()).isEqualTo(NotraiusStatuses.AMENITY_RETIRED);
            verify(amenityRepository, never()).save(any());
        }

        @Test
        @DisplayName("a proposal that never became live cannot be retired — it is not in the catalogue")
        void retireRequiresCatalogueMembership() {
            Amenity cancelled = amenity("POOL", NotraiusStatuses.AMENITY_CANCELLED, OTHER);
            when(amenityRepository.findByIdForUpdate(cancelled.getId())).thenReturn(Optional.of(cancelled));

            assertThatThrownBy(() -> service.retireAmenity(cancelled.getId()))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.AMENITY_INVALID_STATE));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Fixtures
    // ═══════════════════════════════════════════════════════════════════════

    /** The stable client contract is the error CODE, not the prose. */
    private static org.primarion.module.communication.errors.core.ErrorCode errorOf(Throwable thrown) {
        assertThat(thrown).isInstanceOf(AppException.class);
        return ((AppException) thrown).getError();
    }

    private static String upper(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private Entities entity(String code) {
        Entities entity = new Entities();
        entity.setId(UUID.randomUUID());
        entity.setCode(code);
        return entity;
    }

    private Status status(String code) {
        Status status = new Status();
        status.setId(UUID.randomUUID());
        status.setCode(code);
        return status;
    }

    private Type type(String code, Entities entity, String statusCode) {
        Type type = new Type();
        type.setId(UUID.randomUUID());
        type.setCode(code);
        type.setLabel(code);
        type.setEntity(entity);
        type.setStatus(status(statusCode));
        return type;
    }

    private Duration duration(int value, String statusCode) {
        Duration duration = new Duration();
        duration.setId(UUID.randomUUID());
        duration.setType(type("HOUR", entity("DURATION_UNIT"), NotraiusStatuses.TYPE_ACTIVE));
        duration.setValue(value);
        duration.setStatus(status(statusCode));
        return duration;
    }

    private Amenity amenity(String code, String statusCode, UUID proposer) {
        Amenity amenity = new Amenity();
        amenity.setId(UUID.randomUUID());
        amenity.setType(type("COMFORT", entity("AMENITIES"), NotraiusStatuses.TYPE_ACTIVE));
        amenity.setDataType(AmenityDataType.BOOLEAN);
        amenity.setCode(code);
        amenity.setLabel(code);
        amenity.setStatus(status(statusCode));
        amenity.setProposer(proposer);
        return amenity;
    }

    private TypeCreateRequest typeRequest(String entityCode, String code, UUID parentId) {
        return new TypeCreateRequest(entityCode, parentId, code, code.toUpperCase(), null);
    }

    private DurationCreateRequest durationRequest(UUID typeId, int value) {
        return new DurationCreateRequest(typeId, value);
    }

    private AmenityCreateRequest amenityRequest(UUID typeId, String code) {
        return new AmenityCreateRequest(typeId, "BOOLEAN", code, code, null);
    }

}
