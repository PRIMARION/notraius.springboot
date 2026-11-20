package com.notraius.modules.core.config.policies.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A policy as the API speaks it: the standard, its bounds, whether compliance is
 * required, how its value is read, and which world it governs.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PolicyResponse {

    private UUID id;

    private String entityCode;

    private UUID typeId;

    private String typeCode;

    private String code;

    private String title;

    private String description;

    /** Null means unbounded on that side. */
    private BigDecimal min;

    private BigDecimal max;

    private boolean mandatory;

    /** UNITS or AMOUNT. */
    private String unit;

    private Integer version;

    /** Null is the stateless fallback of the resolution rule. */
    private UUID stateId;

    /** ENABLED or DISABLED. */
    private String statusCode;

    private Instant createdAt;

    private Instant updatedAt;
}
