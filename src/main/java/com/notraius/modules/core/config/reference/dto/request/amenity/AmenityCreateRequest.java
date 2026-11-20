package com.notraius.modules.core.config.reference.dto.request.amenity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Submit an amenity (any authenticated tier).
 * <p>
 * The same payload serves both doors: a personal user's submission lands in
 * PENDING and awaits an admin decision, while an admin's own submission is
 * live at birth — staff birth is staff approval (PLAN.md Part 3.3).
 * <p>
 * {@code dataType} is fixed at birth and can never change: flipping a boolean
 * amenity to a string one would reinterpret every holding's stored value, so
 * a different type is a different amenity.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AmenityCreateRequest {

    /** The amenity's kind — a live type under the AMENITIES entity. */
    @NotNull
    private UUID typeId;

    /** BOOLEAN, INTEGER or STRING. Immutable afterwards. */
    @NotBlank
    @Size(max = 12)
    private String dataType;

    @NotBlank
    @Size(max = 50)
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]*$",
            message = "code must start with a letter and contain only letters, digits and underscores")
    private String code;

    @NotBlank
    @Size(max = 100)
    private String label;

    @Size(max = 2000)
    private String description;
}
