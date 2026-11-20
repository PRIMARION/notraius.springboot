package com.notraius.modules.lease.visit.pricings.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One visit offer, as the API speaks it. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class VisitPricingResponse {

    private UUID id;

    private UUID propertyId;

    private UUID typeId;

    /** PHYSICAL or VIRTUAL. */
    private String typeCode;

    private BigDecimal amount;

    /** Inherited from the property — the one money a listing speaks. */
    private UUID currencyId;

    private Integer durationMinutes;

    /** ACTIVE / PAUSED. */
    private String statusCode;

    private Integer version;

    private Instant createdAt;

    private Instant updatedAt;
}
