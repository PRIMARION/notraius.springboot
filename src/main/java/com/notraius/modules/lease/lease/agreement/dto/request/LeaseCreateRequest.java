package com.notraius.modules.lease.lease.agreement.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * The tenant's birth intent (PLAN 6.1): choose a property's pricing and the
 * window the tenancy is meant to cover.
 * <p>
 * <b>The pricing is the whole commercial context.</b> Cadence, rent, currency,
 * {@code checkable} and the state whose policy chain prices the deposits all
 * come from it — there is no settings screen and no second pricing question.
 * A null end date is the open-ended lease, whose calendar the machine generates
 * on a rolling horizon (6.2/6.5).
 * <p>
 * {@code termsId} is the optional general-conditions document the parties
 * accepted; it is stored as a reference, never interpreted here.
 */
@Getter
@Setter
public class LeaseCreateRequest {

    @NotNull
    private UUID pricingId;

    /** The tenancy's first instant; null means the moment of acceptance. */
    private Instant fromDate;

    /** The tenancy's end; null is the open-ended lease. */
    private Instant toDate;

    /** The accepted general-conditions reference, when the flow collected one. */
    private UUID termsId;
}
