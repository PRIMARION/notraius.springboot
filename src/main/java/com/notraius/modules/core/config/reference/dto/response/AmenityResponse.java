package com.notraius.modules.core.config.reference.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * An amenity as the API speaks it — the full lifecycle state plus the
 * proposer, so a client can show "your proposal is pending" without a second
 * lookup.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AmenityResponse {

    private UUID id;

    private UUID typeId;

    private String typeCode;

    /** BOOLEAN, INTEGER or STRING — immutable after birth. */
    private String dataType;

    private String code;

    private String label;

    private String description;

    /** PENDING, ACTIVE, REJECTED, CANCELLED, DISABLED or RETIRED. */
    private String statusCode;

    /** The proposer; null when an admin created the row directly. */
    private UUID proposerId;

    private UUID createdBy;

    private Instant createdAt;

    private Instant updatedAt;
}
