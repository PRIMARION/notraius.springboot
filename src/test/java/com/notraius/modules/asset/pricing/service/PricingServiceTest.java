package com.notraius.modules.asset.pricing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notraius.common.audit.AuditTrail;
import com.notraius.common.NotraiusErrors;
import com.notraius.common.dto.responses.RevisionSummaryResponse;
import com.notraius.common.services.PolicyValueSeeder;
import com.notraius.common.services.RevisionService;
import com.notraius.common.services.StatusResolver;
import com.notraius.database.entities.asset.Pricing;
import com.notraius.database.entities.asset.Property;
import com.notraius.database.entities.asset.PropertyPolicyValue;
import com.notraius.database.entities.config.Policy;
import com.notraius.database.entities.config.PolicyUnit;
import com.notraius.database.entities.reference.Duration;
import com.notraius.database.entities.reference.Entities;
import com.notraius.database.entities.reference.Status;
import com.notraius.database.entities.reference.Type;
import com.notraius.database.entities.reference.Validity;
import com.notraius.database.repositories.asset.PricingRepository;
import com.notraius.database.repositories.asset.PropertyPolicyValueRepository;
import com.notraius.database.repositories.asset.PropertyRepository;
import com.notraius.database.repositories.reference.DurationRepository;
import com.notraius.database.repositories.reference.TypeRepository;
import com.notraius.database.repositories.reference.ValidityRepository;
import com.notraius.modules.asset.pricing.dto.request.PricingCreateRequest;
import com.notraius.modules.asset.pricing.dto.request.PricingUpdateRequest;
import com.notraius.modules.asset.pricing.dto.request.PricingWindowRequest;
import com.notraius.modules.asset.pricing.dto.response.PricingResponse;
import com.notraius.modules.asset.pricing.mapper.PricingMapper;
import com.notraius.modules.core.common.constants.NotraiusActivities;
import com.notraius.modules.core.common.constants.NotraiusStatuses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.notraius.common.audit.RequiredAuditWriter;
import org.primarion.module.errors.AppException;
import org.primarion.module.errors.ConflictException;
import org.primarion.module.errors.ErrorCode;
import org.primarion.module.errors.ForbiddenException;
import org.primarion.module.errors.NotFoundException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Stress check for the pricing sub-module (PLAN.md 4.2): one row per
 * (property, type), a strictly positive amount, a half-open window that answers
 * for exactly one instant, EXPIRED derived rather than stored, and mandatory
 * terms seeded at birth.
 */
@ExtendWith(MockitoExtension.class)
class PricingServiceTest {

    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STRANGER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private PropertyRepository propertyRepository;
    @Mock private PricingRepository pricingRepository;
    @Mock private PropertyPolicyValueRepository valueRepository;
    @Mock private TypeRepository typeRepository;
    @Mock private DurationRepository durationRepository;
    @Mock private ValidityRepository validityRepository;
    @Mock private PolicyValueSeeder policyValueSeeder;
    @Mock private StatusResolver statusResolver;
    @Mock private RequiredAuditWriter auditLogWriter;
    @Mock private RevisionService revisionService;

    private PricingService service;

    @BeforeEach
    void setUp() {
        service = new PricingService(propertyRepository, pricingRepository, valueRepository, typeRepository,
                durationRepository, validityRepository, policyValueSeeder, statusResolver,
                new PricingMapper(), new AuditTrail(new ObjectMapper().findAndRegisterModules(), auditLogWriter),
                revisionService, null, new ObjectMapper().findAndRegisterModules());
        lenient().when(statusResolver.resolve(any(), any()))
                .thenAnswer(invocation -> status(invocation.getArgument(1)));
        lenient().when(statusResolver.openValidity(any(), any())).thenAnswer(invocation -> new Validity());
    }

    // ── Creation ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("a price needs an anchored property — no state, no currency, no price")
    void createRequiresAnchor() {
        Property property = property(NotraiusStatuses.PROPERTY_DRAFT, OWNER, null);
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));

        assertThatThrownBy(() -> service.createPricing(property.getId(), request(), OWNER))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.PROPERTY_STATE_UNRESOLVABLE));
        verify(pricingRepository, never()).save(any());
    }

    @Test
    @DisplayName("one row per (property, type) — a second price is a new window, not a new row")
    void secondPriceForTheSameTypeIsRefused() {
        Property property = anchoredProperty(OWNER);
        Type type = pricingType("NIGHTLY");
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(typeRepository.findById(type.getId())).thenReturn(Optional.of(type));
        when(pricingRepository.existsByPropertyIdAndTypeId(property.getId(), type.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.createPricing(property.getId(), request(type.getId()), OWNER))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.PRICING_TYPE_ALREADY_PRICED));
    }

    @Test
    @DisplayName("a price is never zero — the amount is strictly positive")
    void zeroAmountIsRefused() {
        Property property = anchoredProperty(OWNER);
        Type type = pricingType("NIGHTLY");
        Duration duration = duration(10);
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(typeRepository.findById(type.getId())).thenReturn(Optional.of(type));
        when(durationRepository.findById(duration.getId())).thenReturn(Optional.of(duration));

        PricingCreateRequest request = request(type.getId());
        request.setDurationId(duration.getId());
        request.setAmount(BigDecimal.ZERO);

        assertThatThrownBy(() -> service.createPricing(property.getId(), request, OWNER))
                .isInstanceOf(AppException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.PRICING_AMOUNT_INVALID));
    }

    @Test
    @DisplayName("an inverted window is refused — [from, to) must have a positive length")
    void invertedWindowIsRefused() {
        Property property = anchoredProperty(OWNER);
        Type type = pricingType("NIGHTLY");
        Duration duration = duration(10);
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(typeRepository.findById(type.getId())).thenReturn(Optional.of(type));
        when(durationRepository.findById(duration.getId())).thenReturn(Optional.of(duration));

        Instant from = Instant.parse("2026-06-01T00:00:00Z");
        PricingCreateRequest request = request(type.getId());
        request.setDurationId(duration.getId());
        request.setFromDate(from);
        request.setToDate(from.minus(1, ChronoUnit.DAYS));

        assertThatThrownBy(() -> service.createPricing(property.getId(), request, OWNER))
                .isInstanceOf(AppException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.PRICING_WINDOW_INVALID));
    }

    @Test
    @DisplayName("a type from another namespace cannot price a property")
    void foreignTypeIsRefused() {
        Property property = anchoredProperty(OWNER);
        Type propertyType = type("APARTMENT", entity("PROPERTY"));
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(typeRepository.findById(propertyType.getId())).thenReturn(Optional.of(propertyType));

        assertThatThrownBy(() -> service.createPricing(property.getId(), request(propertyType.getId()), OWNER))
                .isInstanceOf(AppException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.PRICING_TYPE_ENTITY_MISMATCH));
    }

    @Test
    @DisplayName("birth is LIVE at version one and seeds every mandatory standard")
    void birthIsLiveAndSeedsTerms() {
        Property property = anchoredProperty(OWNER);
        Type type = pricingType("NIGHTLY");
        Duration duration = duration(10);
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(typeRepository.findById(type.getId())).thenReturn(Optional.of(type));
        when(durationRepository.findById(duration.getId())).thenReturn(Optional.of(duration));

        PricingCreateRequest request = request(type.getId());
        request.setDurationId(duration.getId());
        PricingResponse created = service.createPricing(property.getId(), request, OWNER);

        assertThat(created.getStatusCode()).isEqualTo(NotraiusStatuses.PRICING_LIVE);
        assertThat(created.getVersion()).isEqualTo(1);
        assertThat(created.getAmount()).isEqualByComparingTo("250.00");
        assertThat(created.getCurrencyId()).isEqualTo(property.getCurrencyId());

        ArgumentCaptor<Pricing> saved = ArgumentCaptor.forClass(Pricing.class);
        verify(pricingRepository).save(saved.capture());
        verify(policyValueSeeder).seedMandatory(saved.getValue(), OWNER);
        verify(validityRepository).save(any(Validity.class));
    }

    @Test
    @DisplayName("only the owner prices the property")
    void strangerCannotPrice() {
        Property property = anchoredProperty(OWNER);
        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));

        assertThatThrownBy(() -> service.createPricing(property.getId(), request(), STRANGER))
                .isInstanceOf(ForbiddenException.class);
    }

    // ── Editing ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("the duration is the unit of count-valued terms, so it locks once they exist")
    void durationChangeIsBlockedByCountValuedTerms() {
        Pricing pricing = pricing(OWNER, NotraiusStatuses.PRICING_LIVE, Instant.now().minus(1, ChronoUnit.DAYS), null);
        Duration other = duration(30);
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(valueRepository.findByPricingId(pricing.getId())).thenReturn(List.of(countValue()));

        PricingUpdateRequest request = new PricingUpdateRequest(null, other.getId(), null);
        assertThatThrownBy(() -> service.updatePricing(pricing.getId(), request, OWNER))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.PRICING_DURATION_CHANGE_BLOCKED));
    }

    @Test
    @DisplayName("an expired price is history: editing it is refused until a window is issued")
    void expiredPricingCannotBeEdited() {
        Pricing pricing = pricing(OWNER, NotraiusStatuses.PRICING_LIVE,
                Instant.now().minus(10, ChronoUnit.DAYS), Instant.now().minus(2, ChronoUnit.DAYS));
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));

        PricingUpdateRequest request = new PricingUpdateRequest(new BigDecimal("300.00"), null, null);
        assertThatThrownBy(() -> service.updatePricing(pricing.getId(), request, OWNER))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.PRICING_EXPIRED));
    }

    @Test
    @DisplayName("an amount edit bumps the version and leaves the window alone")
    void amountEditBumpsVersion() {
        Pricing pricing = pricing(OWNER, NotraiusStatuses.PRICING_LIVE,
                Instant.now().minus(1, ChronoUnit.DAYS), null);
        Validity original = pricing.getValidity();
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));

        PricingResponse updated = service.updatePricing(pricing.getId(),
                new PricingUpdateRequest(new BigDecimal("310.00"), null, null), OWNER);

        assertThat(updated.getAmount()).isEqualByComparingTo("310.00");
        assertThat(updated.getVersion()).isEqualTo(2);
        assertThat(pricing.getValidity()).isSameAs(original);
    }

    // ── Windows ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("issuing a window revives an expired price and retires the previous window")
    void issuingWindowRevives() {
        Instant pastFrom = Instant.now().minus(10, ChronoUnit.DAYS);
        Instant pastTo = Instant.now().minus(2, ChronoUnit.DAYS);
        Pricing pricing = pricing(OWNER, NotraiusStatuses.PRICING_LIVE, pastFrom, pastTo);
        Validity previous = pricing.getValidity();
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));

        PricingResponse revived = service.issueWindow(pricing.getId(),
                new PricingWindowRequest(null, null), OWNER);

        assertThat(revived.getStatusCode()).isEqualTo(NotraiusStatuses.PRICING_LIVE);
        assertThat(revived.getVersion()).isEqualTo(2);
        assertThat(revived.getToDate()).isNull();
        assertThat(pricing.getValidity()).isNotSameAs(previous);
        verify(validityRepository).delete(previous);
    }

    @Test
    @DisplayName("resuming a paused price with no window covering now is refused")
    void resumeRequiresAWindowContainingNow() {
        Pricing pricing = pricing(OWNER, NotraiusStatuses.PRICING_PAUSED,
                Instant.now().minus(10, ChronoUnit.DAYS), Instant.now().minus(2, ChronoUnit.DAYS));
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));

        assertThatThrownBy(() -> service.resumePricing(pricing.getId(), OWNER))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.PRICING_EXPIRED));
    }

    @Test
    @DisplayName("EXPIRED is derived from the clock, never stored")
    void expiredIsDerived() {
        Pricing pricing = pricing(OWNER, NotraiusStatuses.PRICING_LIVE,
                Instant.now().minus(10, ChronoUnit.DAYS), Instant.now().minus(2, ChronoUnit.DAYS));
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(propertyRepository.findById(pricing.getProperty().getId()))
                .thenReturn(Optional.of(pricing.getProperty()));

        PricingResponse read = service.getPricing(pricing.getId(), OWNER);

        assertThat(read.getStatusCode()).isEqualTo(NotraiusStatuses.PRICING_EXPIRED);
        assertThat(pricing.getStatus().getCode()).isEqualTo(NotraiusStatuses.PRICING_LIVE);
    }

    @Test
    @DisplayName("a future window is not yet answering")
    void futureWindowIsExpiredForNow() {
        Pricing pricing = pricing(OWNER, NotraiusStatuses.PRICING_LIVE,
                Instant.now().plus(2, ChronoUnit.DAYS), null);
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(propertyRepository.findById(pricing.getProperty().getId()))
                .thenReturn(Optional.of(pricing.getProperty()));

        assertThat(service.getPricing(pricing.getId(), OWNER).getStatusCode())
                .isEqualTo(NotraiusStatuses.PRICING_EXPIRED);
    }

    // ── Deletion ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("a price that carries terms cannot be deleted — pause is the exit")
    void pricingWithValuesCannotBeDeleted() {
        Pricing pricing = pricing(OWNER, NotraiusStatuses.PRICING_LIVE,
                Instant.now().minus(1, ChronoUnit.DAYS), null);
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(valueRepository.existsByPricingId(pricing.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.deletePricing(pricing.getId(), OWNER))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.PRICING_IN_USE));
        verify(pricingRepository, never()).delete(any());
    }

    @Test
    @DisplayName("an untouched price is removed with its window")
    void untouchedPricingIsRemovedWithItsWindow() {
        Pricing pricing = pricing(OWNER, NotraiusStatuses.PRICING_LIVE,
                Instant.now().minus(1, ChronoUnit.DAYS), null);
        Validity validity = pricing.getValidity();
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(valueRepository.existsByPricingId(pricing.getId())).thenReturn(false);

        service.deletePricing(pricing.getId(), OWNER);

        verify(pricingRepository).delete(pricing);
        verify(validityRepository).delete(validity);
    }

    // ── History: the chain's old side is the OLD truth ─────────────────────

    @Test
    @DisplayName("an edit's history entry states the PREVIOUS amount on its old side")
    void editSnapshotRecordsTheOldTruth() {
        // Regression guard: the snapshot used to be taken after the setters ran,
        // so old_data carried the NEW amount — "the price as of version 1"
        // answered version 2's number forever.
        Pricing pricing = pricing(OWNER, NotraiusStatuses.PRICING_LIVE,
                Instant.now().minus(1, ChronoUnit.DAYS), null);
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));

        service.updatePricing(pricing.getId(),
                new PricingUpdateRequest(new BigDecimal("310.00"), null, null), OWNER);

        ArgumentCaptor<JsonNode> oldSide = ArgumentCaptor.forClass(JsonNode.class);
        ArgumentCaptor<JsonNode> newSide = ArgumentCaptor.forClass(JsonNode.class);
        verify(auditLogWriter).writeRequired(eq(Entities.PRICINGS), eq(NotraiusActivities.Pricings.UPDATE),
                eq(pricing.getId()), eq("UPDATE"), oldSide.capture(), newSide.capture());

        assertThat(oldSide.getValue().get("amount").decimalValue()).isEqualByComparingTo("250.00");
        assertThat(oldSide.getValue().get("version").asInt()).isEqualTo(1);
        assertThat(newSide.getValue().get("amount").decimalValue()).isEqualByComparingTo("310.00");
        assertThat(newSide.getValue().get("version").asInt()).isEqualTo(2);
    }

    // ── Versions as a read parameter ──────────────────────────────────────

    @Test
    @DisplayName("asking for the row's own current version is the live read — no chain lookup")
    void currentVersionIsTheLiveRead() {
        Pricing pricing = pricing(OWNER, NotraiusStatuses.PRICING_LIVE,
                Instant.now().minus(1, ChronoUnit.DAYS), null);
        pricing.setVersion(3);
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(propertyRepository.findById(pricing.getProperty().getId()))
                .thenReturn(Optional.of(pricing.getProperty()));

        PricingResponse response = service.getPricing(pricing.getId(), OWNER, 3);

        assertThat(response.getVersion()).isEqualTo(3);
        verifyNoInteractions(revisionService);
    }

    @Test
    @DisplayName("the owner reads a superseded version from the audit chain")
    void ownerReadsAPastRevision() {
        Pricing pricing = pricing(OWNER, NotraiusStatuses.PRICING_LIVE,
                Instant.now().minus(1, ChronoUnit.DAYS), null);
        pricing.setVersion(3);
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(propertyRepository.findById(pricing.getProperty().getId()))
                .thenReturn(Optional.of(pricing.getProperty()));

        PricingResponse born = new PricingResponse();
        born.setId(pricing.getId());
        born.setAmount(new BigDecimal("250.00"));
        born.setVersion(1);
        when(revisionService.revisionOf(Entities.PRICINGS, pricing.getId(), 1, PricingResponse.class))
                .thenReturn(born);

        PricingResponse response = service.getPricing(pricing.getId(), OWNER, 1);

        assertThat(response.getVersion()).isEqualTo(1);
        assertThat(response.getAmount()).isEqualByComparingTo("250.00");
    }

    @Test
    @DisplayName("a stranger may not read a published price's past — 404, not a retired amount")
    void strangerCannotReadHistory() {
        Property published = property(NotraiusStatuses.PROPERTY_PUBLISHED, OWNER, UUID.randomUUID());
        Pricing pricing = pricing(OWNER, NotraiusStatuses.PRICING_LIVE,
                Instant.now().minus(1, ChronoUnit.DAYS), null);
        pricing.setProperty(published);
        pricing.setVersion(2);
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(propertyRepository.findById(published.getId())).thenReturn(Optional.of(published));

        assertThatThrownBy(() -> service.getPricing(pricing.getId(), STRANGER, 1))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(revisionService);
    }

    @Test
    @DisplayName("a past revision's status is re-derived at THIS instant — one version, one answer")
    void pastRevisionStatusIsDerivedAtReadTime() {
        // The snapshot froze "LIVE" when version 1 was current; its window has
        // since ended. Serving the frozen word would make the same version read
        // LIVE through the history door and EXPIRED through the live one.
        Pricing pricing = pricing(OWNER, NotraiusStatuses.PRICING_LIVE,
                Instant.now().minus(1, ChronoUnit.DAYS), null);
        pricing.setVersion(2);
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(propertyRepository.findById(pricing.getProperty().getId()))
                .thenReturn(Optional.of(pricing.getProperty()));

        PricingResponse superseded = new PricingResponse();
        superseded.setId(pricing.getId());
        superseded.setVersion(1);
        superseded.setStoredStatusCode(NotraiusStatuses.PRICING_LIVE);
        superseded.setStatusCode(NotraiusStatuses.PRICING_LIVE);
        superseded.setFromDate(Instant.now().minus(30, ChronoUnit.DAYS));
        superseded.setToDate(Instant.now().minus(10, ChronoUnit.DAYS));
        when(revisionService.revisionOf(Entities.PRICINGS, pricing.getId(), 1, PricingResponse.class))
                .thenReturn(superseded);

        assertThat(service.getPricing(pricing.getId(), OWNER, 1).getStatusCode())
                .isEqualTo(NotraiusStatuses.PRICING_EXPIRED);
    }

    @Test
    @DisplayName("a paused act inside a live window still reads PAUSED — the clock never invents one")
    void pastRevisionKeepsTheOwnersAct() {
        Pricing pricing = pricing(OWNER, NotraiusStatuses.PRICING_LIVE,
                Instant.now().minus(1, ChronoUnit.DAYS), null);
        pricing.setVersion(2);
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(propertyRepository.findById(pricing.getProperty().getId()))
                .thenReturn(Optional.of(pricing.getProperty()));

        PricingResponse superseded = new PricingResponse();
        superseded.setId(pricing.getId());
        superseded.setVersion(1);
        superseded.setStoredStatusCode(NotraiusStatuses.PRICING_PAUSED);
        superseded.setStatusCode(NotraiusStatuses.PRICING_PAUSED);
        superseded.setFromDate(Instant.now().minus(30, ChronoUnit.DAYS));
        superseded.setToDate(null);
        when(revisionService.revisionOf(Entities.PRICINGS, pricing.getId(), 1, PricingResponse.class))
                .thenReturn(superseded);

        assertThat(service.getPricing(pricing.getId(), OWNER, 1).getStatusCode())
                .isEqualTo(NotraiusStatuses.PRICING_PAUSED);
    }

    @Test
    @DisplayName("a scheduled revision written before its window opened reads its act now that it answers")
    void scheduledRevisionAnswersWhenItsWindowOpens() {
        // The snapshot was taken while the window was still in the future, so its
        // derived word was EXPIRED while the owner's act was LIVE. Today the window
        // answers: the honest answer is LIVE, which only storedStatusCode can say.
        Pricing pricing = pricing(OWNER, NotraiusStatuses.PRICING_LIVE,
                Instant.now().minus(1, ChronoUnit.DAYS), null);
        pricing.setVersion(2);
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(propertyRepository.findById(pricing.getProperty().getId()))
                .thenReturn(Optional.of(pricing.getProperty()));

        PricingResponse superseded = new PricingResponse();
        superseded.setId(pricing.getId());
        superseded.setVersion(1);
        superseded.setStoredStatusCode(NotraiusStatuses.PRICING_LIVE);
        superseded.setStatusCode(NotraiusStatuses.PRICING_EXPIRED);
        superseded.setFromDate(Instant.now().minus(1, ChronoUnit.DAYS));
        superseded.setToDate(null);
        when(revisionService.revisionOf(Entities.PRICINGS, pricing.getId(), 1, PricingResponse.class))
                .thenReturn(superseded);

        assertThat(service.getPricing(pricing.getId(), OWNER, 1).getStatusCode())
                .isEqualTo(NotraiusStatuses.PRICING_LIVE);
    }

    @Test
    @DisplayName("the owner lists the revisions the chain holds — the current one flagged")
    void ownerListsRevisions() {
        Pricing pricing = pricing(OWNER, NotraiusStatuses.PRICING_LIVE,
                Instant.now().minus(1, ChronoUnit.DAYS), null);
        pricing.setVersion(3);
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(propertyRepository.findById(pricing.getProperty().getId()))
                .thenReturn(Optional.of(pricing.getProperty()));
        when(revisionService.revisionsOf(Entities.PRICINGS, pricing.getId(), 3))
                .thenReturn(List.of(
                        new RevisionSummaryResponse(3, Instant.now(), true),
                        new RevisionSummaryResponse(2, Instant.now().minus(1, ChronoUnit.DAYS), false)));

        assertThat(service.listRevisions(pricing.getId(), OWNER))
                .extracting(RevisionSummaryResponse::getVersion)
                .containsExactly(3, 2);
    }

    @Test
    @DisplayName("a stranger cannot enumerate a published price's past — 404, no chain read")
    void strangerCannotListRevisions() {
        Property published = property(NotraiusStatuses.PROPERTY_PUBLISHED, OWNER, UUID.randomUUID());
        Pricing pricing = pricing(OWNER, NotraiusStatuses.PRICING_LIVE,
                Instant.now().minus(1, ChronoUnit.DAYS), null);
        pricing.setProperty(published);
        pricing.setVersion(3);
        when(pricingRepository.findById(pricing.getId())).thenReturn(Optional.of(pricing));
        when(propertyRepository.findById(published.getId())).thenReturn(Optional.of(published));

        assertThatThrownBy(() -> service.listRevisions(pricing.getId(), STRANGER))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(revisionService);
    }

    // ── As-of reads ───────────────────────────────────────────────────────

    @Test
    @DisplayName("the as-of page is what answered then: window and act judged at that instant")
    void asOfPageKeepsOnlyWhatAnsweredThen() {
        Property property = property(NotraiusStatuses.PROPERTY_PUBLISHED, OWNER, UUID.randomUUID());
        Pricing nightly = pricing(OWNER, NotraiusStatuses.PRICING_LIVE,
                Instant.now().minus(10, ChronoUnit.DAYS), null);
        nightly.setProperty(property);
        Pricing monthly = pricing(OWNER, NotraiusStatuses.PRICING_PAUSED,
                Instant.now().minus(10, ChronoUnit.DAYS), null);
        monthly.setProperty(property);
        Instant at = Instant.parse("2026-03-03T12:00:00Z");

        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(pricingRepository.findByPropertyId(property.getId()))
                .thenReturn(List.of(nightly, monthly));

        PricingResponse answered = new PricingResponse();
        answered.setId(nightly.getId());
        answered.setTypeId(nightly.getType().getId());
        answered.setTypeCode("NIGHTLY");
        answered.setStoredStatusCode(NotraiusStatuses.PRICING_LIVE);
        answered.setFromDate(at.minus(30, ChronoUnit.DAYS));
        answered.setToDate(null);

        PricingResponse paused = new PricingResponse();
        paused.setId(monthly.getId());
        paused.setTypeId(monthly.getType().getId());
        paused.setTypeCode("MONTHLY");
        paused.setStoredStatusCode(NotraiusStatuses.PRICING_PAUSED);
        paused.setFromDate(at.minus(30, ChronoUnit.DAYS));
        paused.setToDate(null);

        when(revisionService.statesAt(eq(Entities.PRICINGS), anyCollection(), eq(at),
                eq(PricingResponse.class)))
                .thenReturn(Map.of(nightly.getId(), answered, monthly.getId(), paused));

        // Anyone may read a published property's as-of page; only the price that
        // was answering reads LIVE, and the paused one does not answer at all.
        List<PricingResponse> page = service.answeringAt(property.getId(), null, at, STRANGER);

        assertThat(page).extracting(PricingResponse::getTypeCode).containsExactly("NIGHTLY");
        assertThat(page.getFirst().getStatusCode()).isEqualTo(NotraiusStatuses.PRICING_LIVE);
    }

    @Test
    @DisplayName("a price whose window did not contain the instant does not answer it")
    void asOfPageDropsAWindowThatEndedBefore() {
        Property property = property(NotraiusStatuses.PROPERTY_PUBLISHED, OWNER, UUID.randomUUID());
        Pricing pricing = pricing(OWNER, NotraiusStatuses.PRICING_LIVE,
                Instant.now().minus(10, ChronoUnit.DAYS), null);
        pricing.setProperty(property);
        Instant at = Instant.parse("2026-03-03T12:00:00Z");

        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(pricingRepository.findByPropertyId(property.getId())).thenReturn(List.of(pricing));

        PricingResponse ended = new PricingResponse();
        ended.setId(pricing.getId());
        ended.setStoredStatusCode(NotraiusStatuses.PRICING_LIVE);
        ended.setFromDate(at.minus(30, ChronoUnit.DAYS));
        ended.setToDate(at.minus(1, ChronoUnit.DAYS));
        when(revisionService.statesAt(eq(Entities.PRICINGS), anyCollection(), eq(at),
                eq(PricingResponse.class)))
                .thenReturn(Map.of(pricing.getId(), ended));

        assertThat(service.answeringAt(property.getId(), null, at, OWNER)).isEmpty();
    }

    @Test
    @DisplayName("a price born after the instant is not replayed — the chain simply has no state for it")
    void asOfPageDropsARecordTheChainDidNotHoldYet() {
        Property property = property(NotraiusStatuses.PROPERTY_PUBLISHED, OWNER, UUID.randomUUID());
        Pricing pricing = pricing(OWNER, NotraiusStatuses.PRICING_LIVE,
                Instant.now().minus(10, ChronoUnit.DAYS), null);
        pricing.setProperty(property);
        Instant at = Instant.parse("2026-03-03T12:00:00Z");

        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(pricingRepository.findByPropertyId(property.getId())).thenReturn(List.of(pricing));
        when(revisionService.statesAt(eq(Entities.PRICINGS), anyCollection(), eq(at),
                eq(PricingResponse.class)))
                .thenReturn(Map.of());

        assertThat(service.answeringAt(property.getId(), null, at, STRANGER)).isEmpty();
    }

    @Test
    @DisplayName("the as-of page can be narrowed to one commercial type")
    void asOfPageFiltersByType() {
        Property property = property(NotraiusStatuses.PROPERTY_PUBLISHED, OWNER, UUID.randomUUID());
        Pricing nightly = pricing(OWNER, NotraiusStatuses.PRICING_LIVE,
                Instant.now().minus(10, ChronoUnit.DAYS), null);
        nightly.setProperty(property);
        Pricing monthly = pricing(OWNER, NotraiusStatuses.PRICING_LIVE,
                Instant.now().minus(10, ChronoUnit.DAYS), null);
        monthly.setProperty(property);
        monthly.setType(pricingType("MONTHLY"));
        Instant at = Instant.parse("2026-03-03T12:00:00Z");

        when(propertyRepository.findById(property.getId())).thenReturn(Optional.of(property));
        when(pricingRepository.findByPropertyId(property.getId()))
                .thenReturn(List.of(nightly, monthly));

        PricingResponse n = new PricingResponse();
        n.setId(nightly.getId());
        n.setTypeId(nightly.getType().getId());
        n.setTypeCode("NIGHTLY");
        n.setStoredStatusCode(NotraiusStatuses.PRICING_LIVE);
        n.setFromDate(at.minus(1, ChronoUnit.DAYS));
        PricingResponse m = new PricingResponse();
        m.setId(monthly.getId());
        m.setTypeId(monthly.getType().getId());
        m.setTypeCode("MONTHLY");
        m.setStoredStatusCode(NotraiusStatuses.PRICING_LIVE);
        m.setFromDate(at.minus(1, ChronoUnit.DAYS));

        when(revisionService.statesAt(eq(Entities.PRICINGS), anyCollection(), eq(at),
                eq(PricingResponse.class)))
                .thenReturn(Map.of(nightly.getId(), n, monthly.getId(), m));

        assertThat(service.answeringAt(property.getId(), monthly.getType().getId(), at, OWNER))
                .extracting(PricingResponse::getTypeCode)
                .containsExactly("MONTHLY");
    }

    @Test
    @DisplayName("an as-of read without the instant is refused — that read is the live list")
    void asOfReadNeedsTheInstant() {
        assertThatThrownBy(() -> service.answeringAt(UUID.randomUUID(), null, null, OWNER))
                .isInstanceOf(AppException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.PRICING_WINDOW_INVALID));
        verifyNoInteractions(revisionService);
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private PricingCreateRequest request() {
        return new PricingCreateRequest(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("250.00"),
                false, null, null);
    }

    private PricingCreateRequest request(UUID typeId) {
        PricingCreateRequest request = request();
        request.setTypeId(typeId);
        return request;
    }

    private Pricing pricing(UUID ownerId, String statusCode, Instant from, Instant to) {
        Property property = anchoredProperty(ownerId);
        Validity validity = new Validity();
        validity.setFromDate(from);
        validity.setToDate(to);
        validity.setStatus(status(NotraiusStatuses.PRICING_LIVE));

        Pricing pricing = new Pricing();
        pricing.setId(UUID.randomUUID());
        pricing.setProperty(property);
        pricing.setType(pricingType("NIGHTLY"));
        pricing.setDuration(duration(10));
        pricing.setAmount(new BigDecimal("250.00"));
        pricing.setValidity(validity);
        pricing.setStatus(status(statusCode));
        pricing.setVersion(1);
        return pricing;
    }

    private PropertyPolicyValue countValue() {
        Policy policy = new Policy();
        policy.setId(UUID.randomUUID());
        policy.setCode("ADVANCE");
        policy.setUnit(PolicyUnit.UNITS);
        policy.setVersion(1);
        policy.setStatus(status(NotraiusStatuses.POLICY_ENABLED));

        PropertyPolicyValue value = new PropertyPolicyValue();
        value.setId(UUID.randomUUID());
        value.setPolicy(policy);
        value.setValue(new BigDecimal("1"));
        value.setVersion(1);
        value.setStatus(status(NotraiusStatuses.POLICY_VALUE_ACTIVE));
        return value;
    }

    private Property property(String statusCode, UUID ownerId, UUID stateId) {
        Property property = new Property();
        property.setId(UUID.randomUUID());
        property.setOwnerId(ownerId);
        property.setTitle("Villa");
        property.setStatus(status(statusCode));
        property.setStateId(stateId);
        property.setCurrencyId(stateId != null ? UUID.randomUUID() : null);
        property.setVersion(1);
        return property;
    }

    private Property anchoredProperty(UUID ownerId) {
        return property(NotraiusStatuses.PROPERTY_DRAFT, ownerId, UUID.randomUUID());
    }

    private Duration duration(int value) {
        Duration duration = new Duration();
        duration.setId(UUID.randomUUID());
        duration.setValue(value);
        duration.setType(type("HOUR", entity("DURATIONS")));
        duration.setStatus(status("ACTIVE"));
        return duration;
    }

    private Type pricingType(String code) {
        return type(code, entity("PRICINGS"));
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
