package com.notraius.modules.config.reference.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Edit a live amenity (admin tier — the platform owns the live catalogue, so
 * even the original proposer may not touch it once it is ACTIVE).
 * <p>
 * Only the label and the description. The {@code code} is the machine
 * identity filters and searches key on, and the {@code dataType} interprets
 * every stored holding value; both are immutable after birth.
 * <p>
 * A null field means "leave unchanged".
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AmenityUpdateRequest {

    /** Null = unchanged; a blank label is never a valid label. */
    @Size(max = 100)
    @Pattern(regexp = ".*\\S.*", message = "label must not be blank")
    private String label;

    @Size(max = 2000)
    private String description;
}
