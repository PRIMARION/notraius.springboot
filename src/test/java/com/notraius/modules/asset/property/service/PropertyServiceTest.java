package com.notraius.modules.asset.property.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notraius.common.audit.AuditTrail;
import com.notraius.common.NotraiusErrors;
import com.notraius.common.dto.responses.RevisionSummaryResponse;
import com.notraius.common.integration.LogisticLocationClient;
import com.notraius.common.integration.StateCurrencyResolver;
import com.notraius.common.services.QueryFilters;
import com.notraius.common.services.RevisionService;
import com.notraius.common.services.StatusResolver;
import com.notraius.database.entities.asset.Pricing;
import com.notraius.database.entities.asset.Property;
import com.notraius.database.entities.asset.PropertyAmenity;
import com.notraius.database.entities.lease.Lease;
import com.notraius.database.entities.reference.Amenity;
import com.notraius.database.entities.reference.AmenityDataType;
import com.notraius.database.entities.reference.Entities;
import com.notraius.database.entities.reference.Status;
import com.notraius.database.entities.reference.Type;
import com.notraius.database.entities.reference.Validity;
import com.notraius.database.repositories.asset.PricingRepository;
import com.notraius.database.repositories.asset.PropertyAmenityRepository;
import com.notraius.database.repositories.asset.PropertyRepository;
import com.notraius.database.repositories.reference.AmenityRepository;
import com.notraius.database.repositories.reference.TypeRepository;
import com.notraius.database.repositories.visit.VisitRepository;
import com.notraius.modules.visit.visits.service.VisitService;
import com.notraius.modules.asset.property.dto.request.PropertyAmenityRequest;
import com.notraius.modules.asset.property.dto.request.PropertyCreateRequest;
import com.notraius.modules.asset.property.dto.request.PropertyLocationRequest;
import com.notraius.modules.asset.property.dto.request.PropertyUpdateRequest;
import com.notraius.modules.asset.property.dto.response.PropertyAmenityResponse;
import com.notraius.modules.asset.property.dto.response.PropertyResponse;
import com.notraius.modules.asset.property.dto.response.TenancyResponse;
import com.notraius.modules.asset.property.mapper.PropertyMapper;
import com.notraius.modules.asset.terms.service.TermsService;
import com.notraius.modules.core.common.constants.NotraiusStatuses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.notraius.common.audit.RequiredAuditWriter;
import org.primarion.module.errors.AppException;
import org.primarion.module.errors.ConflictException;
import org.primarion.module.errors.ErrorCode;
import org.primarion.module.errors.ForbiddenException;
import org.primarion.module.utils.security.users.UserClient;
import org.primarion.module.utils.security.users.UserService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stress check for the property sub-module (PLAN.md 4.1): the KYC gate fails
 * closed, a property is born DRAFT and private, the anchors are derived once and
 * then frozen, publishing verifies rather than trusts, and moderation can never
 * be walked around by the owner.
 */
@ExtendWith(MockitoExtension.class)
class PropertyServiceTest {

    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STRANGER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private PropertyRepository propertyRepository;
    @Mock private PricingRepository pricingRepository;
    @Mock private PropertyAmenityRepository amenityHoldingRepository;
    @Mock private AmenityRepository amenityRepository;
    @Mock private TypeRepository typeRepository;
    @Mock private VisitRepository visitRepository;
    @Mock private com.notraius.database.repositories.lease.LeaseRepository leaseRepository;
    @Mock private VisitService visitService;
    @Mock private TermsService termsService;
    @Mock private LogisticLocationClient logisticLocationClient;
    @Mock private StateCurrencyResolver stateCurrencyResolver;
    @Mock private UserService userService;
    @Mock private StatusResolver statusResolver;
    @Mock private RequiredAuditWriter auditLogWriter;
    @Mock private QueryFilters queryFilters;
    @Mock private RevisionService revisionService;

    private PropertyService service;

    @BeforeEach
    void setUp() {
        service = new PropertyService(propertyRepository, pricingRepository, amenityHoldingRepository,
                amenityRepository, typeRepository, visitRepository, leaseRepository, visitService, termsService,
                logisticLocationClient,
                stateCurrencyResolver,
                userService, statusResolver, new PropertyMapper(), new AuditTrail(new ObjectMapper().findAndRegisterModules(), auditLogWriter),
                new com.notraius.config.properties.NotraiusProperties(), queryFilters, revisionService);
        lenient().when(statusResolver.resolve(any(), any()))
                .thenAnswer(invocation -> status(invocation.getArgument(1)));
        // The filters' own law is QueryFiltersTest's subject; here they must only
        // pass the value through so each test exercises the service decision.
        lenient().when(queryFilters.status(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(queryFilters.search(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── Creation and the KYC gate ─────────────────────────────────────────

    @Test
    @DisplayName("an unverified identity cannot list a property")
    void unverifiedOwnerIsRefused() {
        when(userService.isKycVerified(OWNER)).thenReturn(false);

        assertThatThrownBy(() -> service.createProperty(new PropertyCreateRequest("Villa", null, null), OWNER))
                .isInstanceOf(ForbiddenException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.PROPERTY_KYC_REQUIRED));
        verify(propertyRepository, never()).save(any());
    }

    @Test
    @DisplayName("KYC fails closed: an unreachable provider is a refusal, not a pass")
    void kycOutageFailsClosed() {
        when(userService.isKycVerified(OWNER))
                .thenThrow(new UserClient.KycUnavailableException("down", new RuntimeException("timeout")));

        assertThatThrownBy(() -> service.createProperty(new PropertyCreateRequest("Villa", null, null), OWNER))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.PROPERTY_KYC_UNAVAILABLE));
        verify(propertyRepository, never()).save(any());
    }

    @Test
    @DisplayName("birth is DRAFT at version one, owned by the caller and unanchored")
    void birthIsDraftOwnedAndUnanchored() {
        when(userService.isKycVerified(OWNER)).thenReturn(true);

        PropertyResponse created = service.createProperty(
                new PropertyCreateRequest("  Villa  ", "near the lagoon", null), OWNER);

        assertThat(created.getStatusCode()).isEqualTo(NotraiusStatuses.PROPERTY_DRAFT);
        assertThat(created.getVersion()).isEqualTo(1);
        assertThat(created.getOwnerId()).isEqualTo(OWNER);
        assertThat(created.getTitle()).isEqualTo("Villa");
        assertThat(created.getLocationId()).isNull();
        assertThat(created.getCurrencyId()).isNull();
        verify(auditLogWriter).writeRequired(eq(Entities.PROPERTY), eq("PROPERTY.CREATE"), any(),
                eq("CREATE"), any(), any());
    }

    @Test
    @DisplayName("a type from another namespace cannot classify a property")
    void foreignTypeIsRefused() {
        when(userService.isKycVerified(OWNER)).thenReturn(true);
        Type pricingType = type("NIGHTLY", entity("PRICINGS"));
        when(typeRepository.findById(pricingType.getId())).thenReturn(Optional.of(pricingType));

        PropertyCreateRequest request = new PropertyCreateRequest("Villa", null, pricingType.getId());
        assertThatThrownBy(() -> service.createProperty(request, OWNER))
                .isInstanceOf(AppException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.PROPERTY_TYPE_ENTITY_MISMATCH));
    }

    // ── The anchors ───────────────────────────────────────────────────────

    @Test
    @DisplayName("the anchors are derived: location → state → currency, all three at once")
    void locationDerivesStateAndCurrency() {
        Property property = property(NotraiusStatuses.PROPERTY_DRAFT, OWNER);
        UUID locationId = UUID.randomUUID();
        UUID stateId = UUID.randomUUID();
        UUID currencyId = UUID.randomUUID();
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(logisticLocationClient.resolveStateId(locationId)).thenReturn(Optional.of(stateId));
        when(stateCurrencyResolver.currencyForState(stateId)).thenReturn(Optional.of(currencyId));

        PropertyResponse anchored = service.setLocation(property.getId(),
                new PropertyLocationRequest(locationId), OWNER);

        assertThat(anchored.getLocationId()).isEqualTo(locationId);
        assertThat(anchored.getStateId()).isEqualTo(stateId);
        assertThat(anchored.getCurrencyId()).isEqualTo(currencyId);
        assertThat(anchored.getVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("the anchors never change: a second location is refused by name")
    void secondLocationIsRefused() {
        Property property = property(NotraiusStatuses.PROPERTY_DRAFT, OWNER);
        property.setLocationId(UUID.randomUUID());
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));

        assertThatThrownBy(() -> service.setLocation(property.getId(),
                new PropertyLocationRequest(UUID.randomUUID()), OWNER))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.PROPERTY_LOCATION_IMMUTABLE));
    }

    @Test
    @DisplayName("an unknown location is refused, never stored as an opaque orphan")
    void unknownLocationIsRefused() {
        Property property = property(NotraiusStatuses.PROPERTY_DRAFT, OWNER);
        UUID locationId = UUID.randomUUID();
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(logisticLocationClient.resolveStateId(locationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setLocation(property.getId(),
                new PropertyLocationRequest(locationId), OWNER))
                .isInstanceOf(AppException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.PROPERTY_LOCATION_UNRESOLVED));
        assertThat(property.getLocationId()).isNull();
    }

    @Test
    @DisplayName("a state with no resolvable currency cannot anchor a property")
    void unresolvableCurrencyIsRefused() {
        Property property = property(NotraiusStatuses.PROPERTY_DRAFT, OWNER);
        UUID locationId = UUID.randomUUID();
        UUID stateId = UUID.randomUUID();
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(logisticLocationClient.resolveStateId(locationId)).thenReturn(Optional.of(stateId));
        when(stateCurrencyResolver.currencyForState(stateId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setLocation(property.getId(),
                new PropertyLocationRequest(locationId), OWNER))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.PROPERTY_CURRENCY_UNRESOLVED));
        assertThat(property.getStateId()).isNull();
    }

    // ── Publishing ────────────────────────────────────────────────────────

    @Test
    @DisplayName("publishing without a type is refused before the market sees anything")
    void publishWithoutTypeIsRefused() {
        Property property = property(NotraiusStatuses.PROPERTY_DRAFT, OWNER);
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(userService.isKycVerified(OWNER)).thenReturn(true);

        assertThatThrownBy(() -> service.publishProperty(property.getId(), OWNER))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.PROPERTY_INCOMPLETE));
        assertThat(property.getStatus().getCode()).isEqualTo(NotraiusStatuses.PROPERTY_DRAFT);
    }

    @Test
    @DisplayName("publishing an unanchored property is refused: no state, no money, no market")
    void publishWithoutAnchorsIsRefused() {
        Property property = anchoredProperty(OWNER);
        property.setLocationId(null);
        property.setStateId(null);
        property.setCurrencyId(null);
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(userService.isKycVerified(OWNER)).thenReturn(true);

        assertThatThrownBy(() -> service.publishProperty(property.getId(), OWNER))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.PROPERTY_LOCATION_REQUIRED));
    }

    @Test
    @DisplayName("publishing needs a price that answers now")
    void publishWithoutAnsweringPricingIsRefused() {
        Property property = anchoredProperty(OWNER);
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(userService.isKycVerified(OWNER)).thenReturn(true);
        when(pricingRepository.countAnsweringNow(eq(property.getId()),
                eq(NotraiusStatuses.PRICING_LIVE), any())).thenReturn(0L);

        assertThatThrownBy(() -> service.publishProperty(property.getId(), OWNER))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.PROPERTY_INCOMPLETE));
    }

    @Test
    @DisplayName("a complete property publishes and becomes public")
    void completePropertyPublishes() {
        Property property = anchoredProperty(OWNER);
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(userService.isKycVerified(OWNER)).thenReturn(true);
        when(pricingRepository.countAnsweringNow(eq(property.getId()),
                eq(NotraiusStatuses.PRICING_LIVE), any())).thenReturn(1L);

        PropertyResponse published = service.publishProperty(property.getId(), OWNER);

        assertThat(published.getStatusCode()).isEqualTo(NotraiusStatuses.PROPERTY_PUBLISHED);
        verify(termsService).requireMandatoryValuesComplete(property);
    }

    @Test
    @DisplayName("only the owner publishes")
    void strangerCannotPublish() {
        Property property = anchoredProperty(OWNER);
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));

        assertThatThrownBy(() -> service.publishProperty(property.getId(), STRANGER))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("a draft cannot be unpublished — nothing was published")
    void unpublishRequiresPublished() {
        Property property = property(NotraiusStatuses.PROPERTY_DRAFT, OWNER);
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));

        assertThatThrownBy(() -> service.unpublishProperty(property.getId(), OWNER))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.PROPERTY_INVALID_STATE));
    }

    // ── Moderation ────────────────────────────────────────────────────────

    @Test
    @DisplayName("suspension is moderation's, and reinstate lands PAUSED — the owner re-publishes")
    void moderationArcNeverGrantsOwnership() {
        Property property = anchoredProperty(OWNER);
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));

        assertThat(service.suspendProperty(property.getId(), "reported").getStatusCode())
                .isEqualTo(NotraiusStatuses.PROPERTY_SUSPENDED);
        assertThat(service.reinstateProperty(property.getId()).getStatusCode())
                .isEqualTo(NotraiusStatuses.PROPERTY_PAUSED);
    }

    @Test
    @DisplayName("a suspended property cannot be deleted around moderation")
    void suspendedPropertyCannotBeDeleted() {
        Property property = anchoredProperty(OWNER);
        property.setStatus(status(NotraiusStatuses.PROPERTY_SUSPENDED));
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));

        assertThatThrownBy(() -> service.deleteProperty(property.getId(), OWNER))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.PROPERTY_INVALID_STATE));
    }

    @Test
    @DisplayName("a property with a visit in flight cannot be deleted — money is held against it")
    void propertyWithLiveVisitCannotBeDeleted() {
        Property property = anchoredProperty(OWNER);
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(visitRepository.countLiveForProperty(any(), any())).thenReturn(2L);

        assertThatThrownBy(() -> service.deleteProperty(property.getId(), OWNER))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.PROPERTY_IN_USE));
        assertThat(property.getStatus().getCode()).isEqualTo(NotraiusStatuses.PROPERTY_DRAFT);
    }

    @Test
    @DisplayName("delete is the owner's soft exit — the row survives as DELETED")
    void deleteIsSoft() {
        Property property = anchoredProperty(OWNER);
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));

        service.deleteProperty(property.getId(), OWNER);

        assertThat(property.getStatus().getCode()).isEqualTo(NotraiusStatuses.PROPERTY_DELETED);
        verify(propertyRepository, never()).delete(any());
        verify(propertyRepository).save(property);
    }

    @Test
    @DisplayName("delete releases the property's rule selections and amenity holdings")
    void deleteReleasesReferences() {
        Property property = anchoredProperty(OWNER);
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        PropertyAmenity holding = new PropertyAmenity();
        holding.setId(UUID.randomUUID());
        holding.setProperty(property);
        when(amenityHoldingRepository.findByPropertyId(property.getId())).thenReturn(List.of(holding));

        service.deleteProperty(property.getId(), OWNER);

        // The catalogue's in-use locks count these rows by entity, never by the
        // property's status, and the doors that would release them answer 404 for
        // a deleted property — so the deletion is the release (PLAN.md 4.1).
        verify(termsService).releaseSelections(property.getId());
        verify(amenityHoldingRepository).delete(holding);
    }

    @Test
    @DisplayName("DISABLED is deletable — the settlement's word is not a life sentence")
    void disabledPropertyCanBeDeleted() {
        Property property = anchoredProperty(OWNER);
        property.setStatus(status(NotraiusStatuses.PROPERTY_DISABLED));
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(amenityHoldingRepository.findByPropertyId(property.getId())).thenReturn(List.of());

        // A settlement birth leaves the property DISABLED; requiring PUBLISHED
        // first would make deleting a retired home a public act.
        service.deleteProperty(property.getId(), OWNER);

        assertThat(property.getStatus().getCode()).isEqualTo(NotraiusStatuses.PROPERTY_DELETED);
    }

    @Test
    @DisplayName("a draft reads as absent to anyone but its owner — 403 would list every draft there is")
    void draftReadAsAbsentToStrangers() {
        Property property = property(NotraiusStatuses.PROPERTY_DRAFT, OWNER);
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));

        // The same answer the id would get if it had never been created: the
        // difference between 403 and 404 is the whole id space of drafts.
        assertThatThrownBy(() -> service.getProperty(property.getId(), STRANGER))
                .isInstanceOf(AppException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.PROPERTY_NOT_FOUND));

        // Its owner still reads it, and so do the amenity doors that share the gate.
        assertThat(service.getProperty(property.getId(), OWNER).getId()).isEqualTo(property.getId());
        assertThatThrownBy(() -> service.listAmenities(property.getId(), STRANGER))
                .isInstanceOf(AppException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.PROPERTY_NOT_FOUND));
    }

    @Test
    @DisplayName("history listing inherits the single revision's audience — owner yes, stranger 404")
    void revisionListingFollowsTheHistoryGate() {
        Property property = property(NotraiusStatuses.PROPERTY_PUBLISHED, OWNER);
        property.setVersion(2);
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(revisionService.revisionsOf(Entities.PROPERTY, property.getId(), 2)).thenReturn(List.of(
                new RevisionSummaryResponse(2, Instant.now(), true),
                new RevisionSummaryResponse(1, Instant.now().minusSeconds(60), false)));

        assertThatThrownBy(() -> service.listRevisions(property.getId(), STRANGER))
                .isInstanceOf(AppException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.PROPERTY_NOT_FOUND));
        verify(revisionService, never()).revisionsOf(any(), any(), anyInt());

        assertThat(service.listRevisions(property.getId(), OWNER))
                .extracting(RevisionSummaryResponse::getVersion)
                .containsExactly(2, 1);
    }

    @Test
    @DisplayName("a LEASED property reads for everyone — the lease must not lock its own tenant out")
    void leasedPropertyIsPublic() {
        Property property = property(NotraiusStatuses.PROPERTY_LEASED, OWNER);
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(amenityHoldingRepository.findByPropertyId(property.getId())).thenReturn(List.of());

        // The tenant is not the owner; a logged-out visitor is nobody at all.
        assertThat(service.getProperty(property.getId(), STRANGER).getId()).isEqualTo(property.getId());
        assertThat(service.getProperty(property.getId(), null).getId()).isEqualTo(property.getId());
        assertThat(service.listAmenities(property.getId(), null)).isEmpty();
    }

    @Test
    @DisplayName("a draft stays absent to a logged-out caller — anonymous is not a wildcard")
    void draftStaysAbsentToAnonymous() {
        Property property = property(NotraiusStatuses.PROPERTY_DRAFT, OWNER);
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));

        assertThatThrownBy(() -> service.getProperty(property.getId(), null))
                .isInstanceOf(AppException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.PROPERTY_NOT_FOUND));
    }

    @Test
    @DisplayName("staff read a draft — moderation cannot suspend a row it cannot read")
    void staffReadDraft() {
        Property property = property(NotraiusStatuses.PROPERTY_DRAFT, OWNER);
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        authenticateAs("PRIMARION_ADMIN");
        try {
            assertThat(service.getProperty(property.getId(), null).getId()).isEqualTo(property.getId());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("the tenancy door answers the current window — dates only, no tenant, no lease id")
    void tenancyAnswersCurrentWindow() {
        Property property = property(NotraiusStatuses.PROPERTY_LEASED, OWNER);
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant until = Instant.parse("2026-12-31T00:00:00Z");
        when(leaseRepository.findLiveForProperty(property.getId(), NotraiusStatuses.LEASE_LIVE_STATUSES))
                .thenReturn(List.of(lease(from, until)));

        TenancyResponse response = service.readTenancy(property.getId(), null);

        assertThat(response.getPropertyId()).isEqualTo(property.getId());
        assertThat(response.getStatusCode()).isEqualTo(NotraiusStatuses.PROPERTY_LEASED);
        assertThat(response.getLeasedFrom()).isEqualTo(from);
        assertThat(response.getLeasedUntil()).isEqualTo(until);
    }

    @Test
    @DisplayName("an untenanted property answers its status and an empty window, never a second 404")
    void tenancyWithoutLiveLease() {
        Property property = property(NotraiusStatuses.PROPERTY_PUBLISHED, OWNER);
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(leaseRepository.findLiveForProperty(property.getId(), NotraiusStatuses.LEASE_LIVE_STATUSES))
                .thenReturn(List.of());

        TenancyResponse response = service.readTenancy(property.getId(), null);

        assertThat(response.getStatusCode()).isEqualTo(NotraiusStatuses.PROPERTY_PUBLISHED);
        assertThat(response.getLeasedFrom()).isNull();
        assertThat(response.getLeasedUntil()).isNull();
    }

    @Test
    @DisplayName("a deleted property reads as absent")
    void deletedPropertyIsNotFound() {
        Property property = property(NotraiusStatuses.PROPERTY_DELETED, OWNER);
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));

        assertThatThrownBy(() -> service.getProperty(property.getId(), OWNER))
                .isInstanceOf(AppException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.PROPERTY_NOT_FOUND));
    }

    // ── Amenity holdings ──────────────────────────────────────────────────

    @Test
    @DisplayName("an amenity's value must match its declared data type")
    void amenityValueMustFitItsDataType() {
        Property property = anchoredProperty(OWNER);
        Amenity amenity = amenity(AmenityDataType.INTEGER, NotraiusStatuses.AMENITY_ACTIVE);
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(amenityRepository.findById(amenity.getId())).thenReturn(Optional.of(amenity));

        assertThatThrownBy(() -> service.setAmenity(property.getId(),
                new PropertyAmenityRequest(amenity.getId(), "lots"), OWNER))
                .isInstanceOf(AppException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.PROPERTY_AMENITY_VALUE_INVALID));
    }

    @Test
    @DisplayName("a catalogue amenity that is not ACTIVE is not a choice")
    void disabledCatalogueAmenityIsNotSelectable() {
        Property property = anchoredProperty(OWNER);
        Amenity amenity = amenity(AmenityDataType.BOOLEAN, NotraiusStatuses.AMENITY_DISABLED);
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(amenityRepository.findById(amenity.getId())).thenReturn(Optional.of(amenity));

        assertThatThrownBy(() -> service.setAmenity(property.getId(),
                new PropertyAmenityRequest(amenity.getId(), "true"), OWNER))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.PROPERTY_AMENITY_NOT_LIVE));
    }

    @Test
    @DisplayName("selecting a live amenity stores the owner's value and the ACTIVE holding")
    void selectingAmenityStoresValue() {
        Property property = anchoredProperty(OWNER);
        Amenity amenity = amenity(AmenityDataType.BOOLEAN, NotraiusStatuses.AMENITY_ACTIVE);
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(amenityRepository.findById(amenity.getId())).thenReturn(Optional.of(amenity));
        when(amenityHoldingRepository.findByPropertyIdAndAmenityId(property.getId(), amenity.getId()))
                .thenReturn(Optional.empty());

        PropertyAmenityResponse response = service.setAmenity(property.getId(),
                new PropertyAmenityRequest(amenity.getId(), "true"), OWNER);

        assertThat(response.getValue()).isEqualTo("true");
        assertThat(response.getStatusCode()).isEqualTo(NotraiusStatuses.PROPERTY_AMENITY_ACTIVE);
        verify(amenityHoldingRepository).save(any(PropertyAmenity.class));
    }

    @Test
    @DisplayName("the catalogue's switch suppresses only what the owner had active")
    void suppressCascadeRespectsTheOwnersOwnChoice() {
        Amenity amenity = amenity(AmenityDataType.BOOLEAN, NotraiusStatuses.AMENITY_DISABLED);
        PropertyAmenity active = holding(amenity, NotraiusStatuses.PROPERTY_AMENITY_ACTIVE);
        PropertyAmenity ownerOff = holding(amenity, NotraiusStatuses.PROPERTY_AMENITY_DISABLED);
        when(amenityHoldingRepository.findByAmenityIdPage(any(), any(), any()))
                .thenReturn(List.of(active, ownerOff));

        int suppressed = service.suppressAmenityHoldings(amenity.getId());

        assertThat(suppressed).isEqualTo(1);
        assertThat(active.getStatus().getCode()).isEqualTo(NotraiusStatuses.PROPERTY_AMENITY_SUPPRESSED);
        assertThat(ownerOff.getStatus().getCode()).isEqualTo(NotraiusStatuses.PROPERTY_AMENITY_DISABLED);
    }

    @Test
    @DisplayName("re-enabling the catalogue restores exactly what it suppressed")
    void restoreCascadeTouchesOnlySuppressedHoldings() {
        Amenity amenity = amenity(AmenityDataType.BOOLEAN, NotraiusStatuses.AMENITY_ACTIVE);
        PropertyAmenity suppressed = holding(amenity, NotraiusStatuses.PROPERTY_AMENITY_SUPPRESSED);
        PropertyAmenity ownerOff = holding(amenity, NotraiusStatuses.PROPERTY_AMENITY_DISABLED);
        when(amenityHoldingRepository.findByAmenityIdPage(any(), any(), any()))
                .thenReturn(List.of(suppressed, ownerOff));

        int restored = service.restoreAmenityHoldings(amenity.getId());

        assertThat(restored).isEqualTo(1);
        assertThat(suppressed.getStatus().getCode()).isEqualTo(NotraiusStatuses.PROPERTY_AMENITY_ACTIVE);
        assertThat(ownerOff.getStatus().getCode()).isEqualTo(NotraiusStatuses.PROPERTY_AMENITY_DISABLED);
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private Lease lease(Instant from, Instant until) {
        Validity validity = new Validity();
        validity.setFromDate(from);
        validity.setToDate(until);
        Lease lease = new Lease();
        lease.setId(UUID.randomUUID());
        lease.setValidity(validity);
        return lease;
    }

    private void authenticateAs(String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                UUID.randomUUID().toString(), "n/a",
                List.of(new SimpleGrantedAuthority(authority))));
    }

    private Property property(String statusCode, UUID ownerId) {
        Property property = new Property();
        property.setId(UUID.randomUUID());
        property.setOwnerId(ownerId);
        property.setTitle("Villa");
        property.setStatus(status(statusCode));
        property.setVersion(1);
        return property;
    }

    private Property anchoredProperty(UUID ownerId) {
        Property property = property(NotraiusStatuses.PROPERTY_DRAFT, ownerId);
        property.setType(type("APARTMENT", entity("PROPERTY")));
        property.setLocationId(UUID.randomUUID());
        property.setStateId(UUID.randomUUID());
        property.setCurrencyId(UUID.randomUUID());
        return property;
    }

    private PropertyAmenity holding(Amenity amenity, String statusCode) {
        PropertyAmenity holding = new PropertyAmenity();
        holding.setId(UUID.randomUUID());
        holding.setAmenity(amenity);
        holding.setValue("true");
        holding.setStatus(status(statusCode));
        return holding;
    }

    private Amenity amenity(AmenityDataType dataType, String statusCode) {
        Amenity amenity = new Amenity();
        amenity.setId(UUID.randomUUID());
        amenity.setCode("POOL");
        amenity.setLabel("Pool");
        amenity.setDataType(dataType);
        amenity.setStatus(status(statusCode));
        return amenity;
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

    private static ErrorCode errorOf(Throwable thrown) {
        assertThat(thrown).isInstanceOf(AppException.class);
        return ((AppException) thrown).getError();
    }
}
