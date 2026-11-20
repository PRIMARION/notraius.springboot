package com.notraius.modules.lease.lease.agreement.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One condition report, as its parties read it.
 * <p>
 * {@code status} is PENDING until the counterpart confirms it, and
 * {@code createdBy} is the author — the tenant for a move-in report, the
 * landlord for a move-out one. The pair is the evidence trail the settlement's
 * adjudications stand on.
 */
@Getter
@Setter
public class LeaseConditionResponse {

    private UUID id;
    private UUID leaseId;
    private String type;
    private String status;
    private String notes;
    private UUID createdBy;
    private Integer version;
    private Instant createdAt;
}
