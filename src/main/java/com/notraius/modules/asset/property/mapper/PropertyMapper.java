package com.notraius.modules.asset.property.mapper;

import com.notraius.database.entities.asset.Property;
import com.notraius.database.entities.asset.PropertyAmenity;
import com.notraius.database.entities.lease.Lease;
import com.notraius.database.entities.reference.Amenity;
import com.notraius.database.entities.reference.Type;
import com.notraius.modules.asset.property.dto.response.PropertyAmenityResponse;
import com.notraius.modules.asset.property.dto.response.PropertyResponse;
import com.notraius.modules.asset.property.dto.response.TenancyResponse;
import org.springframework.stereotype.Component;

/** Row → DTO for properties and their amenity holdings. Pure projection. */
@Component
public class PropertyMapper {

    public PropertyResponse toResponse(Property property) {
        Type type = property.getType();
        return new PropertyResponse(
                property.getId(),
                property.getOwnerId(),
                property.getTitle(),
                property.getDescription(),
                property.getStatus() != null ? property.getStatus().getCode() : null,
                type != null ? type.getId() : null,
                type != null ? type.getCode() : null,
                property.getLocationId(),
                property.getStateId(),
                property.getCurrencyId(),
                property.getVersion(),
                property.getCreatedAt(),
                property.getUpdatedAt()
        );
    }

    /**
     * The public occupancy projection (UPGRADE.md Table 3): the property's own
     * status plus the current tenancy's window. Dates only — the tenant, the
     * lease id and every amount stay behind the party-only lease door.
     */
    public TenancyResponse toTenancyResponse(Property property, Lease lease) {
        boolean windowed = lease != null && lease.getValidity() != null;
        return new TenancyResponse(
                property.getId(),
                property.getStatus() != null ? property.getStatus().getCode() : null,
                windowed ? lease.getValidity().getFromDate() : null,
                windowed ? lease.getValidity().getToDate() : null
        );
    }

    public PropertyAmenityResponse toAmenityResponse(PropertyAmenity holding) {
        Amenity amenity = holding.getAmenity();
        return new PropertyAmenityResponse(
                amenity != null ? amenity.getId() : null,
                amenity != null ? amenity.getCode() : null,
                // The label is the catalogue's answer (/amenities), not this row's.
                amenity != null && amenity.getDataType() != null ? amenity.getDataType().name() : null,
                holding.getValue(),
                holding.getStatus() != null ? holding.getStatus().getCode() : null
        );
    }
}
