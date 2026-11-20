package com.notraius.modules.lease.visit.pricings.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Edit the price or the length of a visit offer. Absent fields are untouched.
 * <p>
 * Both edits are safe to make at any time because every visit snapshots the
 * amount and the window it was sold with: a new price applies to future
 * bookings, never to money already taken.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class VisitPricingUpdateRequest {

    @DecimalMin(value = "0.01", message = "a visit price must be strictly positive")
    private BigDecimal amount;

    @Min(value = 1, message = "a visit must last at least one minute")
    private Integer durationMinutes;
}
