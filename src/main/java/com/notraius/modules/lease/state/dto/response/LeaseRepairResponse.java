package com.notraius.modules.lease.state.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One repair of the move-out negotiation (PLAN 6.7).
 * <p>
 * {@code chargeBreakdownId} is the money row the charge rides — null for a
 * repair with no charge — and it is the bridge to the charge itself: the billed
 * amount and its payer are that installment row's columns, read at
 * {@code /leases/{leaseId}/installments}, not copied here.
 * <p>
 * Only tenant-chargeable repairs enter the settlement's debts; the landlord's
 * own works are outside the balance sheet but never outside their deadline.
 */
@Getter
@Setter
public class LeaseRepairResponse {

    private UUID id;
    private UUID leaseId;
    private UUID conditionId;
    private String title;
    private String description;
    private String status;
    /** The installment row the charge rides, if any; it holds the amount and payer. */
    private UUID chargeBreakdownId;

    private Integer version;
    private Instant createdAt;
}
