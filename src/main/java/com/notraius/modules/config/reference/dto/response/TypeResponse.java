package com.notraius.modules.config.reference.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A type as the API speaks it. The parent is carried as an id so a client can
 * rebuild the tree from a flat page instead of walking it row by row.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TypeResponse {

    private UUID id;

    /** Namespace of this kind (PROPERTY, PRICINGS, DURATION_UNIT, …). */
    private String entityCode;

    /** Parent kind; null at a root. */
    private UUID parentId;

    private String code;

    private String label;

    private String description;

    /** ACTIVE or RETIRED. */
    private String statusCode;

    private UUID createdBy;

    private Instant createdAt;

    private Instant updatedAt;
}
