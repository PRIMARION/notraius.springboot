package com.notraius.modules.visit.visits.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Request a visit — the act that IS the payment (PLAN.md 5.1): there is no
 * "request, then invoice, then chase". The visitor names the price they saw and
 * the slot they want; the backend charges them and holds the money.
 * <p>
 * The end of the window is optional and defaults to the start plus the price's
 * own duration: a visit lasts what the owner said it lasts, and a client cannot
 * stretch it by sending a longer window. PLANNING later edits the slot — that
 * is a different act with its own turn rules (5.2).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class VisitRequestCreateRequest {

    /** The visit price being booked (physical or virtual). */
    @NotNull
    private UUID pricingId;

    /** When the visit is wanted. */
    @NotNull
    private Instant fromDate;

    /** Optional; defaults to {@code fromDate + the pricing's duration}. */
    private Instant toDate;
}
