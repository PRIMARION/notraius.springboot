package com.notraius.modules.asset.property.dto.request;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Edit a property's content. Every absent field is left untouched — a partial
 * edit never rewrites what it was not asked about.
 * <p>
 * Changing the TYPE on a published property re-runs the completeness gate: the
 * type decides which policy standards apply, and a published property may not
 * silently fall out of compliance.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PropertyUpdateRequest {

    @Size(max = 255)
    private String title;

    @Size(max = 5000)
    private String description;

    private UUID typeId;
}
