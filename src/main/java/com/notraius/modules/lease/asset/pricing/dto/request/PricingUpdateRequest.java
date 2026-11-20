package com.notraius.modules.lease.asset.pricing.dto.request;

import jakarta.validation.constraints.DecimalMin;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Edit the amount, the duration or the checkable flag. Absent fields are left
 * untouched; the window is NOT here — stretching a validity in place is not an
 * act this system offers, a new window is issued instead (its own endpoint, its
 * own version).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PricingUpdateRequest {

    @DecimalMin(value = "0.01", message = "a pricing amount must be strictly positive")
    private BigDecimal amount;

    private UUID durationId;

    private Boolean checkable;
}
