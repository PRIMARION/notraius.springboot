package com.notraius.modules.lease.visit.visits.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import com.notraius.modules.lease.visit.visits.service.VisitNextActions;
import java.util.List;

/**
 * A visit as the API speaks it.
 * <p>
 * Three identities travel side by side, and they are three different people on
 * purpose: the {@code visitorId} who paid, the {@code assigneeId} who is
 * operationally responsible (null until 5.2 stamps one), and the
 * {@code ownerId} who is the beneficiary of the money. Settlement pays the last
 * one — never the visit's own counterpart.
 * <p>
 * Every relation travels as an id — {@code propertyId} and {@code pricingId} are
 * the bridges a client follows to read the property and the offer. What is not
 * copied is their content: no title, no type code, no duration. What has no
 * other door is kept inline — the agreed window and its status code (validity),
 * the amount and currency the visitor was charged, and the claim's clocks.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class VisitResponse {

    private UUID id;

    private UUID visitorId;

    /** The operational counterpart; null until an owner/agent assigns one. */
    private UUID assigneeId;

    /** The property owner at request time — the beneficiary snapshot. */
    private UUID ownerId;

    /**
     * The bridge to the property: fetch its details with this id. The title is
     * deliberately not copied here — it is the property's answer, and it would go
     * stale the day the owner edits it.
     */
    private UUID propertyId;

    /** The visit pricing that was booked; it names the type and the duration. */
    private UUID pricingId;

    /** What the visitor was actually charged. */
    private BigDecimal amount;

    private UUID currencyId;

    /** UNPAID / REQUESTED / ASSIGNED / PLANNING / CONFIRMED / STARTED / COMPLETED / EXPIRED / CANCELLED. */
    private String statusCode;

    private Instant fromDate;

    private Instant toDate;

    /** Pay's payment id; null while the funding is unknown. */
    private UUID paymentId;

    /** The STARTED claim (5.3): who claimed the visit is happening, and when. */
    private Instant startedAt;

    /** The countersignature; null means the claim has not been answered yet. */
    private Instant startConfirmedAt;

    /** When COMPLETED landed — by the mutual confirm or by the hour. */
    private Instant completedAt;

    private Integer version;

    private Instant createdAt;

    private Instant updatedAt;

    /**
     * What this caller may do with this visit now — a hint, never a gate, and
     * never part of the row: it is attached by the read doors for the caller who
     * asked, so a write's answer and the audit snapshot leave it out entirely.
     * Tokens are {@code VisitNextActions}' vocabulary (ASSIGN, ACCEPT, START,
     * ASSESS, CANCEL, …).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private java.util.List<String> nextActions;
}
