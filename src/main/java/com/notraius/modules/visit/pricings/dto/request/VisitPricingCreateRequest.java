package com.notraius.modules.visit.pricings.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Offer a visit price on a property: the kind (physical or virtual), what it
 * costs, and how long the visit lasts in plain minutes.
 * <p>
 * No currency field, deliberately: the visit is charged in the property's
 * currency, derived from its location once and frozen (PLAN 4.1), so there is
 * one money per property and nothing to disagree with.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class VisitPricingCreateRequest {

    /** PHYSICAL or VIRTUAL — a type under VISIT_PRICINGS. */
    @NotNull
    private UUID typeId;

    @NotNull
    @DecimalMin(value = "0.01", message = "a visit price must be strictly positive")
    private BigDecimal amount;

    @NotNull
    @Min(value = 1, message = "a visit must last at least one minute")
    private Integer durationMinutes;
}
