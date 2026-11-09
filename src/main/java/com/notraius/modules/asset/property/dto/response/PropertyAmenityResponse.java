package com.notraius.modules.asset.property.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * One amenity the property holds: the amenity's id and code, the value, and
 * whose switch is off.
 * <p>
 * The label and the catalogue's own text are {@code /amenities}' answer, so this
 * row carries the id and the code only. {@code dataType} stays: it is the
 * vocabulary the stored value must be read in (BOOLEAN / INTEGER / STRING), not
 * a copy of catalogue content.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PropertyAmenityResponse {

    private UUID amenityId;

    private String amenityCode;

    /** BOOLEAN / INTEGER / STRING — how the value must be read. */
    private String dataType;

    private String value;

    /** ACTIVE / DISABLED (owner) / SUPPRESSED (catalogue). */
    private String statusCode;
}
