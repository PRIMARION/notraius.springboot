package com.notraius.modules.lease.asset.pricing.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Issue a fresh validity against the same pricing row — the only way a price
 * answers again after its window ended, and the normal shape of a price change.
 * The version bumps, the previous window stays reconstructible from the log, and
 * the boundary instant belongs to the NEW window (half-open handover).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PricingWindowRequest {

    /** Null means "from now". */
    private Instant fromDate;

    /** Null means permanent. */
    private Instant toDate;
}
