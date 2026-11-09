package com.notraius.modules.lease.agreement.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One deposit standard's counter on a lease (PLAN 6.4).
 * <p>
 * {@code balance} is the counter in the policy's unit — months of advance, or
 * the month-equivalents an AMOUNT hull covers — never a price. {@code amount}
 * is the custody actually born for this standard, summed from its held-back
 * split ledger; it is the frozen figure's only readable home on this side,
 * because the money itself lives in pay's escrows.
 */
@Getter
@Setter
public class LeaseDepositResponse {

    private UUID id;
    private UUID policyId;
    private String policyCode;
    private String typeCode;
    /** The counter, in the policy's unit. */
    private BigDecimal balance;
    private String status;
    /** How many born splits stand for this standard. */
    private int splits;
    /** The custody born for this standard in the property's currency. */
    private BigDecimal amount;
}
