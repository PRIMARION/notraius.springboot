package com.notraius.modules.lease.asset.property.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Select (or re-value) one of the property's amenity holdings. The value is
 * kept as text and validated against the amenity's own data type — the same
 * string carries {@code true}, {@code 2} and {@code 24/7}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PropertyAmenityRequest {

    @NotNull
    private UUID amenityId;

    @Size(max = 255)
    private String value;
}
