package com.notraius.modules.lease.finance.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One bill of the lease's calendar (PLAN 6.2/6.3/6.7).
 * <p>
 * {@code userId} means two things by type, exactly as the entity's own comment
 * says: the <em>recipient</em> of an INSTALLMENT, the declared <em>payer</em> of
 * a REPAIR or SETTLEMENT debt. {@code escrowPaymentId}/{@code escrowSplitId} are
 * written only when the miss was satisfied from custody, and they are the ledger
 * thread tying "this rent" to "that exact escrow".
 * <p>
 * {@code settledAt} is the answer's instant; {@code assessmentFiled} says the
 * miss was already judged, so no second pass can judge the same installment
 * twice.
 */
@Getter
@Setter
public class LeaseInstallmentResponse {

    private UUID id;
    private UUID leaseId;
    private String title;
    private String type;
    private String status;
    private BigDecimal amount;
    private UUID userId;
    private Instant deadline;
    private Integer periodIndex;
    private Instant periodStart;
    private Instant periodEnd;
    private UUID escrowPaymentId;
    private UUID escrowSplitId;
    private Instant settledAt;
    private boolean assessmentFiled;
    private Integer version;
}
