package com.notraius.modules.lease.lease.finance.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The receiver's cash confirmation result (PLAN 6.8).
 * <p>
 * A short cash claim is a <em>recorded fact</em>, never a settled row: the
 * response says what was claimed, what the bill still owes, and whether the
 * claim completed the due figure. Only {@code settled=true} changed the
 * breakdown's status.
 */
@Getter
@Setter
public class LeaseCashConfirmResponse {

    private UUID breakdownId;
    private UUID leaseId;
    private String status;
    private BigDecimal amountDue;
    private BigDecimal amountClaimed;
    private BigDecimal outstanding;
    private boolean settled;
    private Instant settledAt;
}
