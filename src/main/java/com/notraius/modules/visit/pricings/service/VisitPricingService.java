package com.notraius.modules.visit.pricings.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.notraius.common.audit.AuditTrail;
import com.notraius.common.NotraiusErrors;
import com.notraius.common.services.StatusResolver;
import com.notraius.database.entities.asset.Property;
import com.notraius.database.entities.reference.Entities;
import com.notraius.database.entities.reference.Type;
import com.notraius.database.entities.visit.VisitPricing;
import com.notraius.database.repositories.asset.PropertyRepository;
import com.notraius.database.repositories.reference.TypeRepository;
import com.notraius.database.repositories.visit.VisitPricingRepository;
import com.notraius.database.repositories.visit.VisitRepository;
import com.notraius.modules.core.common.constants.NotraiusActivities;
import com.notraius.modules.core.common.constants.NotraiusStatuses;
import com.notraius.modules.visit.pricings.dto.request.VisitPricingCreateRequest;
import com.notraius.modules.visit.pricings.dto.request.VisitPricingUpdateRequest;
import com.notraius.modules.visit.pricings.dto.response.VisitPricingResponse;
import com.notraius.modules.visit.pricings.mapper.VisitPricingMapper;
import lombok.extern.slf4j.Slf4j;
import org.primarion.module.access.consent.service.OwnershipGuard;
import org.primarion.module.errors.BadRequestException;
import org.primarion.module.errors.ConflictException;
import org.primarion.module.errors.ForbiddenException;
import org.primarion.module.errors.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The owner's side of the visit price (PLAN.md 5.1): one physical and one
 * virtual offer per property, each with an amount and a plain minute duration.
 * <p>
 * <b>The price is the owner's, the money is the property's.</b> There is no
 * currency here — the charge is made in the property's currency, and a property's
 * currency is derived once and frozen (4.1), so a visit can never be sold in a
 * second, disagreeing unit.
 * <p>
 * <b>Edits never rewrite a sale.</b> A visit snapshots the amount and the window
 * it was sold with, so re-pricing is always safe: it applies to the next visitor,
 * never to money already held.
 * <p>
 * <b>Pause is the exit, deletion is the exception.</b> A price that already sold
 * a visit cannot be removed — the visit keeps its reference and its row must keep
 * resolving — so the reversible switch is what the plan offers first.
 */
@Service
@Slf4j
public class VisitPricingService extends OwnershipGuard {

    private final VisitPricingRepository visitPricingRepository;
    private final VisitRepository visitRepository;
    private final PropertyRepository propertyRepository;
    private final TypeRepository typeRepository;
    private final StatusResolver statusResolver;
    private final VisitPricingMapper visitPricingMapper;
    private final AuditTrail auditTrail;

    public VisitPricingService(VisitPricingRepository visitPricingRepository,
                               VisitRepository visitRepository,
                               PropertyRepository propertyRepository,
                               TypeRepository typeRepository,
                               StatusResolver statusResolver,
                               VisitPricingMapper visitPricingMapper,
                               AuditTrail auditTrail) {
        this.visitPricingRepository = visitPricingRepository;
        this.visitRepository = visitRepository;
        this.propertyRepository = propertyRepository;
        this.typeRepository = typeRepository;
        this.statusResolver = statusResolver;
        this.visitPricingMapper = visitPricingMapper;
        this.auditTrail = auditTrail;
    }

    /** Offer a visit price. One per (property, kind): editing replaces, never duplicates. */
    @Transactional
    public VisitPricingResponse createPricing(UUID propertyId, VisitPricingCreateRequest request, UUID callerId) {
        Property property = requireOwnedProperty(propertyId, callerId);
        Type type = requireVisitType(request.getTypeId());
        if (visitPricingRepository.existsByPropertyIdAndTypeId(propertyId, type.getId())) {
            throw new ConflictException(NotraiusErrors.VISIT_PRICING_ALREADY_PRICED,
                    "Property " + propertyId + " already offers a " + type.getCode() + " visit price");
        }

        VisitPricing pricing = new VisitPricing();
        pricing.setProperty(property);
        pricing.setType(type);
        pricing.setAmount(requirePositiveAmount(request.getAmount()));
        pricing.setDurationMinutes(requirePositiveDuration(request.getDurationMinutes()));
        pricing.setStatus(statusResolver.resolve(Entities.VISIT_PRICINGS, NotraiusStatuses.VISIT_PRICING_ACTIVE));
        pricing.setVersion(1);
        pricing.setCreatedBy(callerId);
        visitPricingRepository.save(pricing);

        VisitPricingResponse response = visitPricingMapper.toResponse(pricing);
        audit(NotraiusActivities.Visits.PRICING_CREATE, pricing.getId(), "CREATE", response, null);
        log.info("Visit pricing created: id={} property={} kind={} amount={} minutes={}",
                pricing.getId(), propertyId, type.getCode(), pricing.getAmount(),
                pricing.getDurationMinutes());
        return response;
    }

    /** Edit the amount or the duration. Both apply to future bookings only. */
    @Transactional
    public VisitPricingResponse updatePricing(UUID pricingId, VisitPricingUpdateRequest request, UUID callerId) {
        VisitPricing pricing = requireOwnedPricing(pricingId, callerId);

        // Snapshot BEFORE the first mutation: the chain's old side has to be the
        // old truth, not the amount the caller just sent.
        VisitPricingResponse before = visitPricingMapper.toResponse(pricing);

        boolean changed = false;
        if (request.getAmount() != null) {
            BigDecimal amount = requirePositiveAmount(request.getAmount());
            if (amount.compareTo(pricing.getAmount()) != 0) {
                pricing.setAmount(amount);
                changed = true;
            }
        }
        if (request.getDurationMinutes() != null) {
            Integer minutes = requirePositiveDuration(request.getDurationMinutes());
            if (!minutes.equals(pricing.getDurationMinutes())) {
                pricing.setDurationMinutes(minutes);
                changed = true;
            }
        }
        if (!changed) {
            return before;
        }

        pricing.setVersion(pricing.getVersion() + 1);
        visitPricingRepository.save(pricing);

        VisitPricingResponse after = visitPricingMapper.toResponse(pricing);
        audit(NotraiusActivities.Visits.PRICING_UPDATE, pricingId, "UPDATE", after, before);
        return after;
    }

    /** Stop offering a visit kind without forgetting its price. */
    @Transactional
    public VisitPricingResponse pausePricing(UUID pricingId, UUID callerId) {
        VisitPricing pricing = requireOwnedPricing(pricingId, callerId);
        requireStatus(pricing, NotraiusStatuses.VISIT_PRICING_ACTIVE, "pause");

        VisitPricingResponse before = visitPricingMapper.toResponse(pricing);
        pricing.setStatus(statusResolver.resolve(Entities.VISIT_PRICINGS, NotraiusStatuses.VISIT_PRICING_PAUSED));
        pricing.setVersion(pricing.getVersion() + 1);
        visitPricingRepository.save(pricing);

        VisitPricingResponse after = visitPricingMapper.toResponse(pricing);
        audit(NotraiusActivities.Visits.PRICING_PAUSE, pricingId, "PAUSE", after, before);
        return after;
    }

    @Transactional
    public VisitPricingResponse resumePricing(UUID pricingId, UUID callerId) {
        VisitPricing pricing = requireOwnedPricing(pricingId, callerId);
        requireStatus(pricing, NotraiusStatuses.VISIT_PRICING_PAUSED, "resume");

        VisitPricingResponse before = visitPricingMapper.toResponse(pricing);
        pricing.setStatus(statusResolver.resolve(Entities.VISIT_PRICINGS, NotraiusStatuses.VISIT_PRICING_ACTIVE));
        pricing.setVersion(pricing.getVersion() + 1);
        visitPricingRepository.save(pricing);

        VisitPricingResponse after = visitPricingMapper.toResponse(pricing);
        audit(NotraiusActivities.Visits.PRICING_RESUME, pricingId, "RESUME", after, before);
        return after;
    }

    /** Remove an offer nothing was sold at. A sold offer is paused instead. */
    @Transactional
    public void deletePricing(UUID pricingId, UUID callerId) {
        VisitPricing pricing = requireOwnedPricing(pricingId, callerId);
        if (visitRepository.existsByPricingId(pricingId)) {
            throw new ConflictException(NotraiusErrors.VISIT_PRICING_IN_USE,
                    "Visit price " + pricingId + " already sold a visit; pause it instead");
        }

        VisitPricingResponse before = visitPricingMapper.toResponse(pricing);
        visitPricingRepository.delete(pricing);
        audit(NotraiusActivities.Visits.PRICING_DELETE, pricingId, "DELETE", null, before);
    }

    /** A published property shows its visit prices to anyone; a draft to its owner. */
    @Transactional(readOnly = true)
    public List<VisitPricingResponse> listPricings(UUID propertyId, UUID callerId) {
        Property property = requireReadableProperty(propertyId, callerId);
        return visitPricingRepository.findByPropertyId(property.getId()).stream()
                .map(visitPricingMapper::toResponse)
                .toList();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // OwnershipGuard — the property's owner
    // ═══════════════════════════════════════════════════════════════════════

    /** args[0]: the property, args[1]: the caller id. One property, one owner. */
    @Override
    public boolean canAccess(Object... args) {
        if (args.length < 2) {
            return false;
        }
        if (!(args[0] instanceof Property property) || !(args[1] instanceof UUID callerId)) {
            return false;
        }
        return callerId != null && callerId.equals(property.getOwnerId());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private VisitPricing requireOwnedPricing(UUID pricingId, UUID callerId) {
        VisitPricing pricing = visitPricingRepository.findById(pricingId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.VISIT_PRICING_NOT_FOUND,
                        "Visit pricing not found: " + pricingId));
        requireOwnership("Only the property's owner may change its visit prices", pricing.getProperty(), callerId);
        return pricing;
    }

    private Property requireOwnedProperty(UUID propertyId, UUID callerId) {
        Property property = requireProperty(propertyId);
        requireOwnership("Only the property's owner may change its visit prices", property, callerId);
        if (NotraiusStatuses.PROPERTY_SUSPENDED.equals(statusCodeOf(property))) {
            throw new ConflictException(NotraiusErrors.PROPERTY_INVALID_STATE,
                    "Property " + propertyId + " is suspended; its visit prices cannot change");
        }
        return property;
    }

    private Property requireReadableProperty(UUID propertyId, UUID callerId) {
        Property property = requireProperty(propertyId);
        if (!NotraiusStatuses.PROPERTY_PUBLISHED.equals(statusCodeOf(property))) {
            // A draft answers as absent — 403 would confirm the id exists, which
            // is the anti-enumeration rule PropertyService.requireUnpublishedInvisible
            // sets for GET /properties/{id}. Same answer for its sub-resources.
            try {
                requireOwnership("Only the property's owner may read its visit prices", property, callerId);
            } catch (ForbiddenException e) {
                throw new NotFoundException(NotraiusErrors.PROPERTY_NOT_FOUND,
                        "Property not found: " + propertyId);
            }
        }
        return property;
    }

    private Property requireProperty(UUID propertyId) {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.PROPERTY_NOT_FOUND,
                        "Property not found: " + propertyId));
        if (NotraiusStatuses.PROPERTY_DELETED.equals(statusCodeOf(property))) {
            throw new NotFoundException(NotraiusErrors.PROPERTY_NOT_FOUND,
                    "Property not found: " + propertyId);
        }
        return property;
    }

    private Type requireVisitType(UUID typeId) {
        Type type = typeRepository.findById(typeId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.VISIT_PRICING_TYPE_NOT_FOUND,
                        "Visit kind not found: " + typeId));
        String entityCode = type.getEntity() != null ? type.getEntity().getCode() : null;
        if (!Entities.VISIT_PRICINGS.equals(entityCode)) {
            throw new BadRequestException(NotraiusErrors.VISIT_PRICING_TYPE_ENTITY_MISMATCH,
                    "Type '" + type.getCode() + "' belongs to " + entityCode + ", not VISIT_PRICINGS");
        }
        if (!NotraiusStatuses.TYPE_ACTIVE.equals(statusCodeOf(type))) {
            throw new ConflictException(NotraiusErrors.VISIT_PRICING_TYPE_NOT_LIVE,
                    "Visit kind '" + type.getCode() + "' is " + statusCodeOf(type));
        }
        return type;
    }

    private BigDecimal requirePositiveAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException(NotraiusErrors.VISIT_PRICING_AMOUNT_INVALID,
                    "Amount " + amount + " is not strictly positive");
        }
        return amount;
    }

    private Integer requirePositiveDuration(Integer minutes) {
        if (minutes == null || minutes <= 0) {
            throw new BadRequestException(NotraiusErrors.VISIT_PRICING_DURATION_INVALID,
                    "Duration " + minutes + " is not a positive number of minutes");
        }
        return minutes;
    }

    private void requireStatus(VisitPricing pricing, String expected, String operation) {
        String actual = statusCodeOf(pricing);
        if (!expected.equals(actual)) {
            throw new ConflictException(NotraiusErrors.VISIT_PRICING_NOT_LIVE,
                    "Visit price is " + actual + "; " + operation + " requires " + expected);
        }
    }

    private String statusCodeOf(VisitPricing pricing) {
        return pricing.getStatus() != null ? pricing.getStatus().getCode() : null;
    }

    private String statusCodeOf(Property property) {
        return property.getStatus() != null ? property.getStatus().getCode() : null;
    }

    private String statusCodeOf(Type type) {
        return type.getStatus() != null ? type.getStatus().getCode() : null;
    }

    private void audit(String activityCode, UUID referenceId, String operation, Object after, Object before) {
        auditTrail.record(Entities.VISIT_PRICINGS, activityCode, referenceId, operation, before, after);
    }
}
