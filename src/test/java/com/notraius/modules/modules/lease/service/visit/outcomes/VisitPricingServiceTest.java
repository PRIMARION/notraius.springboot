package com.notraius.modules.modules.lease.service.visit.outcomes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notraius.common.audit.AuditTrail;
import com.notraius.common.NotraiusErrors;
import com.notraius.common.services.logs.StatusResolver;
import com.notraius.database.entities.asset.asset.Property;
import com.notraius.database.entities.lease.reference.amenity.Entities;
import com.notraius.database.entities.lease.reference.status.Status;
import com.notraius.database.entities.lease.reference.status.Type;
import com.notraius.database.entities.lease.visit.VisitPricing;
import com.notraius.database.repositories.asset.asset.PropertyRepository;
import com.notraius.database.repositories.lease.reference.duration.TypeRepository;
import com.notraius.database.repositories.lease.visit.VisitPricingRepository;
import com.notraius.database.repositories.lease.visit.VisitRepository;
import com.notraius.modules.core.core.common.constants.NotraiusStatuses;
import com.notraius.modules.lease.visit.pricings.dto.request.VisitPricingCreateRequest;
import com.notraius.modules.lease.visit.pricings.dto.request.VisitPricingUpdateRequest;
import com.notraius.modules.lease.visit.pricings.dto.VisitPricingResponse;
import com.notraius.modules.lease.visit.pricings.VisitPricingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.primarion.module.app.access.consent.AuditLogWriter;
import org.primarion.module.communication.errors.exception.AppException;
import org.primarion.module.communication.errors.client.ConflictException;
import org.primarion.module.communication.errors.core.ErrorCode;
import org.primarion.module.communication.errors.client.ForbiddenException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.notraius.modules.lease.visit.pricings.VisitPricingService;

/**
 * Stress check for the visit price (PLAN.md 5.1 + DATABASE 4.1): one offer per
 * (property, kind), a strictly positive amount and minute duration, the
 * property's currency inherited rather than chosen, and pause as the reversal
 * while a sold offer can never be removed.
 */
@ExtendWith(MockitoExtension.class)
class VisitPricingServiceTest {

    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STRANGER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private VisitPricingRepository visitPricingRepository;
    @Mock private VisitRepository visitRepository;
    @Mock private PropertyRepository propertyRepository;
    @Mock private TypeRepository typeRepository;
    @Mock private StatusResolver statusResolver;
    @Mock private AuditLogWriter auditLogWriter;

    private VisitPricingService service;

    @BeforeEach
    void setUp() {
        service = new VisitPricingService(visitPricingRepository, visitRepository, propertyRepository,
                typeRepository, statusResolver, new VisitPricingMapper(), new AuditTrail(new ObjectMapper().findAndRegisterModules(), auditLogWriter));
        lenient().when(statusResolver.resolve(any(), any()))
                .thenAnswer(invocation -> status(invocation.getArgument(1)));
    }

    @Test
    @DisplayName("a visit price is born ACTIVE at version one, in the property's currency")
    void birthIsActiveAtVersionOne() {
        Property property = property();
        Type type = visitType("PHYSICAL");
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(typeRepository.findById(type.getId())).thenReturn(Optional.of(type));

        VisitPricingResponse created = service.createPricing(property.getId(),
                new VisitPricingCreateRequest(type.getId(), new BigDecimal("25.00"), 30), OWNER);

        assertThat(created.getStatusCode()).isEqualTo(NotraiusStatuses.VISIT_PRICING_ACTIVE);
        assertThat(created.getVersion()).isEqualTo(1);
        assertThat(created.getCurrencyId()).isEqualTo(property.getCurrencyId());
        assertThat(created.getAmount()).isEqualByComparingTo("25.00");
        assertThat(created.getDurationMinutes()).isEqualTo(30);
        verify(visitPricingRepository).save(any(VisitPricing.class));
    }

    @Test
    @DisplayName("a visit kind from another namespace is refused")
    void foreignTypeIsRefused() {
        Property property = property();
        Type propertyType = type("APARTMENT", entity("PROPERTY"));
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(typeRepository.findById(propertyType.getId())).thenReturn(Optional.of(propertyType));

        assertThatThrownBy(() -> service.createPricing(property.getId(),
                new VisitPricingCreateRequest(propertyType.getId(), new BigDecimal("25.00"), 30), OWNER))
                .isInstanceOf(AppException.class)
                .satisfies(t -> assertThat(errorOf(t))
                        .isEqualTo(NotraiusErrors.VISIT_PRICING_TYPE_ENTITY_MISMATCH));
    }

    @Test
    @DisplayName("one offer per kind — a second physical price is refused")
    void duplicateKindIsRefused() {
        Property property = property();
        Type type = visitType("PHYSICAL");
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(typeRepository.findById(type.getId())).thenReturn(Optional.of(type));
        when(visitPricingRepository.existsByPropertyIdAndTypeId(property.getId(), type.getId()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.createPricing(property.getId(),
                new VisitPricingCreateRequest(type.getId(), new BigDecimal("25.00"), 30), OWNER))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t))
                        .isEqualTo(NotraiusErrors.VISIT_PRICING_ALREADY_PRICED));
        verify(visitPricingRepository, never()).save(any());
    }

    @Test
    @DisplayName("a zero price or a zero duration is refused by name")
    void nonPositiveInputsAreRefused() {
        Property property = property();
        Type type = visitType("VIRTUAL");
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(typeRepository.findById(type.getId())).thenReturn(Optional.of(type));

        assertThatThrownBy(() -> service.createPricing(property.getId(),
                new VisitPricingCreateRequest(type.getId(), BigDecimal.ZERO, 30), OWNER))
                .isInstanceOf(AppException.class)
                .satisfies(t -> assertThat(errorOf(t))
                        .isEqualTo(NotraiusErrors.VISIT_PRICING_AMOUNT_INVALID));

        assertThatThrownBy(() -> service.createPricing(property.getId(),
                new VisitPricingCreateRequest(type.getId(), new BigDecimal("25.00"), 0), OWNER))
                .isInstanceOf(AppException.class)
                .satisfies(t -> assertThat(errorOf(t))
                        .isEqualTo(NotraiusErrors.VISIT_PRICING_DURATION_INVALID));
    }

    @Test
    @DisplayName("an edit bumps the version and applies to future bookings only")
    void editBumpsVersion() {
        VisitPricing pricing = pricing(NotraiusStatuses.VISIT_PRICING_ACTIVE, OWNER);
        when(visitPricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));

        VisitPricingResponse updated = service.updatePricing(pricing.getId(),
                new VisitPricingUpdateRequest(new BigDecimal("30.00"), 45), OWNER);

        assertThat(updated.getVersion()).isEqualTo(2);
        assertThat(updated.getAmount()).isEqualByComparingTo("30.00");
        assertThat(updated.getDurationMinutes()).isEqualTo(45);
    }

    @Test
    @DisplayName("pause and resume are a round trip; pause requires ACTIVE")
    void pauseAndResumeRoundTrip() {
        VisitPricing pricing = pricing(NotraiusStatuses.VISIT_PRICING_ACTIVE, OWNER);
        when(visitPricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));

        assertThat(service.pausePricing(pricing.getId(), OWNER).getStatusCode())
                .isEqualTo(NotraiusStatuses.VISIT_PRICING_PAUSED);
        assertThat(service.resumePricing(pricing.getId(), OWNER).getStatusCode())
                .isEqualTo(NotraiusStatuses.VISIT_PRICING_ACTIVE);
    }

    @Test
    @DisplayName("a price that already sold a visit is paused, never removed")
    void soldPricingCannotBeDeleted() {
        VisitPricing pricing = pricing(NotraiusStatuses.VISIT_PRICING_ACTIVE, OWNER);
        when(visitPricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(visitRepository.existsByPricingId(pricing.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.deletePricing(pricing.getId(), OWNER))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.VISIT_PRICING_IN_USE));
        verify(visitPricingRepository, never()).delete(any());
    }

    @Test
    @DisplayName("only the property's owner prices a visit")
    void strangerCannotPrice() {
        Property property = property();
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));

        assertThatThrownBy(() -> service.createPricing(property.getId(),
                new VisitPricingCreateRequest(UUID.randomUUID(), new BigDecimal("25.00"), 30), STRANGER))
                .isInstanceOf(ForbiddenException.class);
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private Property property() {
        Property property = new Property();
        property.setId(UUID.randomUUID());
        property.setOwnerId(OWNER);
        property.setTitle("Villa");
        property.setCurrencyId(UUID.randomUUID());
        property.setStatus(status(NotraiusStatuses.PROPERTY_PUBLISHED));
        return property;
    }

    private VisitPricing pricing(String statusCode, UUID ownerId) {
        VisitPricing pricing = new VisitPricing();
        pricing.setId(UUID.randomUUID());
        pricing.setProperty(property());
        pricing.setType(visitType("PHYSICAL"));
        pricing.setAmount(new BigDecimal("25.00"));
        pricing.setDurationMinutes(30);
        pricing.setVersion(1);
        pricing.setStatus(status(statusCode));
        return pricing;
    }

    private Type visitType(String code) {
        return type(code, entity("VISIT_PRICINGS"));
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
