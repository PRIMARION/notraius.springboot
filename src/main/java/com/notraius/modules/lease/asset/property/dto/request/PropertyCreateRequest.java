package com.notraius.modules.lease.asset.property.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;
import java.util.Optional;

/**
 * Save a property (PLAN.md 4.1's save-first). Only a title is required: the row
 * is born DRAFT, invisible to the market and complete-able at leisure. The
 * location is deliberately absent — setting the anchors is its own one-time act
 * ({@code PUT /{propertyId}/location}), because it can never be repeated.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PropertyCreateRequest {

    @NotBlank
    @Size(max = 255)
    private String title;

    @Size(max = 5000)
    private String description;

    /** Optional while drafting; a type under the PROPERTY entity, ACTIVE. */
    private UUID typeId;
}
