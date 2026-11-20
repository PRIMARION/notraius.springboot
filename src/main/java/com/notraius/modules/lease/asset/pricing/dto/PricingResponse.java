package com.notraius.modules.lease.asset.pricing.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A pricing as the API speaks it: its own row, plus what it points at.
 * <p>
 * The duration is an id, not a copy of the duration row: {@code /durations}
 * serves its value and unit, so a client fetches it by the id it already has.
 * The same restraint is why the currency is only the id inherited from the
 * property: the money's vocabulary is reference data, its rows are not.
 * <p>
 * Kept inline because no door serves them: the window ({@code fromDate},
 * {@code toDate}) and {@code statusCode}, the last one derived from that window
 * at the instant of the read.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PricingResponse {

    private UUID id;

    private UUID propertyId;

    /** Inherited from the property — never chosen per pricing. */
    private UUID currencyId;

    private UUID typeId;

    private String typeCode;

    /** The duration that gives the price a body; read it at {@code /durations}. */
    private UUID durationId;

    private BigDecimal amount;

    private boolean checkable;

    private Integer version;

    private Instant fromDate;

    /** Null means the price is permanent. */
    private Instant toDate;

    /** LIVE / PAUSED / EXPIRED — what the price reads as at the instant of the read. */
    private String statusCode;

    /**
     * The act the owner last performed: LIVE or PAUSED, never the clock's word.
     * <p>
     * Both halves are published because {@code statusCode} is a function of three
     * things — this act, the window, and the instant of the read — and a caller
     * holding only the function's output cannot tell "the owner paused it" from
     * "the window ended", which are different facts with different exits. It is
     * also what makes a past revision answerable: the chain stores this act, so
     * re-deriving a historical {@code statusCode} at today's instant gives the
     * same answer the live door would give for the same version.
     */
    private String storedStatusCode;

    private Instant createdAt;

    private Instant updatedAt;
}
