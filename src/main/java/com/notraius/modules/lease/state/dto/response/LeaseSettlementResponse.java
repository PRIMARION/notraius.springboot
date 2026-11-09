package com.notraius.modules.lease.state.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The end-of-life walk, as its parties read it (PLAN 6.7).
 * <p>
 * {@code tenantDebt} is the outstanding billed total net of nothing — the exact
 * sum of the rows still owed by the tenant (unpaid installments plus
 * tenant-chargeable repairs). {@code landlordDebt} is what the landlord owes the
 * tenant: the still-HELD custody plus any unearned rent a reeled horizon left
 * behind. {@code heldCustody} is the slice of that debt still sitting in escrow.
 * The two figures are shown apart because the golden rule never nets them.
 * <p>
 * {@code deadline} is the billed sides' fortnight; {@code moveOutDeadline} is
 * the landlord's week for conditions and repairs.
 */
@Getter
@Setter
public class LeaseSettlementResponse {

    private UUID id;
    private UUID leaseId;
    private String status;
    private Instant deadline;
    private Instant moveOutDeadline;
    private Instant confirmedAt;
    private UUID confirmedBy;
    private BigDecimal tenantDebt;
    private BigDecimal landlordDebt;
    private BigDecimal heldCustody;
    private Integer version;
    private Instant createdAt;
}
