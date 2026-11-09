package com.notraius.modules.asset.pricing.mapper;

import com.notraius.database.entities.asset.Pricing;
import com.notraius.database.entities.reference.Type;
import com.notraius.database.entities.reference.Validity;
import com.notraius.modules.asset.pricing.dto.response.PricingResponse;
import org.springframework.stereotype.Component;

/**
 * Row → DTO for a pricing.
 * <p>
 * The status is passed in rather than read off the row, because the row only
 * persists acts (LIVE/PAUSED) while the clock's EXPIRED is derived from the
 * window at the instant of the read — one status stands for one instant.
 */
@Component
public class PricingMapper {

    public PricingResponse toResponse(Pricing pricing, String effectiveStatusCode) {
        Type type = pricing.getType();
        Validity validity = pricing.getValidity();
        return new PricingResponse(
                pricing.getId(),
                pricing.getProperty() != null ? pricing.getProperty().getId() : null,
                pricing.getProperty() != null ? pricing.getProperty().getCurrencyId() : null,
                type != null ? type.getId() : null,
                type != null ? type.getCode() : null,
                // The duration is an id here; its value and unit are /durations' answer.
                pricing.getDuration() != null ? pricing.getDuration().getId() : null,
                pricing.getAmount(),
                pricing.isCheckable(),
                pricing.getVersion(),
                validity != null ? validity.getFromDate() : null,
                validity != null ? validity.getToDate() : null,
                effectiveStatusCode,
                // The act itself, so a revision can re-derive the effective status at
                // today's instant instead of freezing yesterday's verdict (see
                // PricingResponse.storedStatusCode and PricingService.getPricing).
                pricing.getStatus() != null ? pricing.getStatus().getCode() : null,
                pricing.getCreatedAt(),
                pricing.getUpdatedAt()
        );
    }
}
