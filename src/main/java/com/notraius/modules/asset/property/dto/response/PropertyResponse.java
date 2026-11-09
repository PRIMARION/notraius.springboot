package com.notraius.modules.asset.property.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A property as the API speaks it. There is no currency input anywhere in this
 * module's requests, and this response carries it read-only: the location
 * resolved to a state, the state to a currency, and the backend applied it.
 * <p>
 * The row and nothing else: a property's prices belong to the pricing door, and
 * copying them in here would make this answer go stale against a clock it does
 * not own, for data its own write paths never touch.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PropertyResponse {

    private UUID id;

    /** The single truth of ownership (the visit module settles to this id). */
    private UUID ownerId;

    private String title;

    private String description;

    /** DRAFT / PUBLISHED / PAUSED / SUSPENDED / DELETED. */
    private String statusCode;

    private UUID typeId;

    private String typeCode;

    /** Opaque logistics reference; null until the anchors are set. */
    private UUID locationId;

    /** Derived from the location; never edited. */
    private UUID stateId;

    /** Derived from the state; never edited. */
    private UUID currencyId;

    private Integer version;

    private Instant createdAt;

    private Instant updatedAt;
}
