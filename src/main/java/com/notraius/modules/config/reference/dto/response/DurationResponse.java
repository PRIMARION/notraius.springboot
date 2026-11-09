package com.notraius.modules.config.reference.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A duration as the API speaks it: the unit type (id + code, so a client can
 * read "HOUR" without a second call) and how many of it. The unit's label is
 * {@code /types}' answer, not this row's.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DurationResponse {

    private UUID id;

    private UUID typeId;

    /** The unit's code — SECOND, MINUTE, HOUR, DAY, MONTH, … */
    private String typeCode;

    /** How many units; null only for a schema-tolerated drafting row. */
    private Integer value;

    /** ACTIVE or RETIRED. */
    private String statusCode;

    private UUID createdBy;

    private Instant createdAt;

    private Instant updatedAt;
}
