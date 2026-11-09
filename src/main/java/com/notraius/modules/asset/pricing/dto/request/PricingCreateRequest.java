package com.notraius.modules.asset.pricing.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Define one pricing row for the property: the commercial type, the duration
 * that gives it a body, the amount, and the window it answers in.
 * <p>
 * The amount is strictly positive — zero is an aberration, never a placeholder
 * (PLAN.md 4.2). The window is half-open {@code [from, to)}; a null end means
 * permanent and a null start means "from now".
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PricingCreateRequest {

    /** The commercial kind (NIGHTLY, MONTHLY) — a type under PRICINGS. */
    @NotNull
    private UUID typeId;

    /** The physics of one unit (ten hours, one month). */
    @NotNull
    private UUID durationId;

    @NotNull
    @DecimalMin(value = "0.01", message = "a pricing amount must be strictly positive")
    private BigDecimal amount;

    /** Whether leases born here run the move-in/move-out condition stage. */
    private boolean checkable;

    /** Window start; null means now. */
    private Instant fromDate;

    /** Window end; null means permanent. */
    private Instant toDate;
}
