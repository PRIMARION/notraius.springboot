package com.notraius.modules.visit.pricings.mapper;

import com.notraius.database.entities.asset.Property;
import com.notraius.database.entities.reference.Type;
import com.notraius.database.entities.visit.VisitPricing;
import com.notraius.modules.visit.pricings.dto.response.VisitPricingResponse;
import org.springframework.stereotype.Component;

/** Row → DTO for a visit price. */
@Component
public class VisitPricingMapper {

    public VisitPricingResponse toResponse(VisitPricing pricing) {
        Property property = pricing.getProperty();
        Type type = pricing.getType();
        return new VisitPricingResponse(
                pricing.getId(),
                property != null ? property.getId() : null,
                type != null ? type.getId() : null,
                type != null ? type.getCode() : null,
                pricing.getAmount(),
                property != null ? property.getCurrencyId() : null,
                pricing.getDurationMinutes(),
                pricing.getStatus() != null ? pricing.getStatus().getCode() : null,
                pricing.getVersion(),
                pricing.getCreatedAt(),
                pricing.getUpdatedAt()
        );
    }
}
