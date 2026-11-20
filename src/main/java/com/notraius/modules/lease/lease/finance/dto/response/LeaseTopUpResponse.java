package com.notraius.modules.lease.lease.finance.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One top-up act (PLAN 6.3), as its parties read it.
 * <p>
 * {@code status} is PENDING while the funding's outcome is unknown (the row was
 * persisted before the call, so its own id is the reference a retry asks pay
 * about), FUNDED once its splits are in the ledger, FAILED when pay refused.
 * {@code paymentId} is pay's own identity, written when the funding stands.
 */
@Getter
@Setter
public class LeaseTopUpResponse {

    private UUID id;
    private UUID leaseId;
    private UUID depositId;
    private BigDecimal amount;
    private int units;
    private UUID paymentId;
    private String status;
    private Integer version;
    private Instant createdAt;
}
