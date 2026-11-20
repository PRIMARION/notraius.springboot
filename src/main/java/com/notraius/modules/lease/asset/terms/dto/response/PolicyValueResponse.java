package com.notraius.modules.lease.asset.terms.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One value the owner set for one standard on one pricing: the standard's id and
 * code, the number itself, and the row's own state.
 * <p>
 * The standard's title, unit, bounds and mandatory flag are {@code /policies}'
 * answer — the client has {@code policyId} and can read them there. Repeating
 * them here would freeze a copy of a policy that may be edited tomorrow.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PolicyValueResponse {

    private UUID id;

    private UUID pricingId;

    private UUID policyId;

    /** The standard's code — reference vocabulary, kept so a row reads alone. */
    private String policyCode;

    private BigDecimal value;

    /** ACTIVE / PAUSED / FLAGGED. */
    private String statusCode;

    private Integer version;

    private Instant createdAt;

    private Instant updatedAt;
}
