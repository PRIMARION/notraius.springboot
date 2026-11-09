package com.notraius.modules.visit.visits.mapper;

import com.notraius.database.entities.reference.Validity;
import com.notraius.database.entities.visit.Visit;
import com.notraius.database.entities.visit.VisitPricing;
import com.notraius.modules.visit.visits.dto.response.VisitResponse;
import org.springframework.stereotype.Component;

/**
 * Row → DTO for a visit. Pure projection: no clock, no policy, and nothing the
 * row's own ids can fetch elsewhere.
 */
@Component
public class VisitMapper {

    public VisitResponse toResponse(Visit visit) {
        VisitPricing pricing = visit.getPricing();
        Validity validity = visit.getValidity();
        return new VisitResponse(
                visit.getId(),
                visit.getVisitorId(),
                visit.getUserId(),
                visit.getOwnerId(),
                // Both bridges are ids: the property's and the offer's details are
                // their own doors' answer, not copies taken here.
                pricing != null && pricing.getProperty() != null ? pricing.getProperty().getId() : null,
                pricing != null ? pricing.getId() : null,
                visit.getAmount(),
                visit.getCurrencyId(),
                visit.getStatus() != null ? visit.getStatus().getCode() : null,
                validity != null ? validity.getFromDate() : null,
                validity != null ? validity.getToDate() : null,
                visit.getPaymentId(),
                visit.getStartedAt(),
                visit.getStartConfirmedAt(),
                visit.getCompletedAt(),
                visit.getVersion(),
                visit.getCreatedAt(),
                visit.getUpdatedAt(),
                // The caller's next acts are not the row's: the read doors attach
                // them, so a mapper-built response never claims them.
                null
        );
    }
}
