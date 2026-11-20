package com.notraius.modules.lease.lease.agreement.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.notraius.modules.lease.lease.agreement.dto.response.LeaseDepositResponse;
import com.notraius.modules.lease.lease.agreement.service.LeaseNextActions;

/**
 * One lease, as its parties read it.
 * <p>
 * Three ids, three meanings (PLAN 6.9): {@code tenantId} pays and is refunded,
 * {@code ownerId} is the beneficiary snapshot the born custody releases to, and
 * the recipient of each billed row is the installment's own {@code userId} —
 * which is why no field here is called "landlord".
 * <p>
 * {@code fundingResolved} separates the two PENDING shapes 6.1 insists on
 * distinguishing: funded and waiting for the owner's confirmation, versus a
 * funding call that never answered (its truth is still pay's to tell, and the
 * sweep asks again). {@code matrixTotal} is the sum of the born splits — never a
 * re-derivation, so a pricing edited tomorrow cannot rewrite what was funded.
 * <p>
 * The property and the offer travel as bridges ({@code propertyId},
 * {@code pricingId}); their titles are their own doors' answers. The figures
 * that <i>cannot</i> be asked elsewhere are copied here, because the rows behind
 * those bridges move: {@code rentAmount} and {@code checkable} are the terms
 * frozen at birth, and {@code matrixTotal} is what the birth actually funded.
 * Reading the live pricing would answer today's offer to a tenant who signed
 * something else.
 */
@Getter
@Setter
public class LeaseResponse {

    private UUID id;
    private UUID tenantId;
    private UUID ownerId;
    /** The bridge to the property; read its details there. */
    private UUID propertyId;
    private UUID pricingId;
    private String status;
    private Integer version;
    /**
     * The rent agreed at birth, in the payment's money — the figure the calendar
     * bills for the whole tenancy. Served here because no other door can state
     * it: the pricing it was read from is editable in place, so asking
     * {@code /pricings/{id}} answers today's offer, not what this tenant signed.
     */
    private BigDecimal rentAmount;
    /**
     * Whether <i>this</i> tenancy owes the condition stage — frozen at birth
     * from the pricing (V38), never re-read from it. The pricing's own flag
     * stays the offer and can change; this cannot.
     */
    private boolean checkable;
    /**
     * The billing cadence signed at birth (V39): the unit code (MONTH, HOUR, …)
     * and its multiplier. The calendar derives every period from these, so a
     * price re-pointed at another duration changes the next offer, never a
     * running tenancy's schedule.
     */
    private String periodUnit;
    private Integer periodValue;
    private Instant fromDate;
    private Instant toDate;
    private Instant createdAt;
    /** What the birth actually funded (sum of born splits); null until splits are known. */
    private BigDecimal matrixTotal;
    /** Whether pay's funding has been adopted into this side's ledger. */
    private boolean fundingResolved;
    private List<LeaseDepositResponse> deposits = new ArrayList<>();

    /**
     * What this caller may do with this lease now — a hint, never a gate, and
     * never part of the row: the read doors attach it for the caller who asked,
     * so a write's answer and the audit snapshot leave it out. Tokens are
     * {@code LeaseNextActions}' vocabulary (CONFIRM, CANCEL, ACCEPT_IMPORT, …).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<String> nextActions;
}
