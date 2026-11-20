package com.notraius.modules.core.config.reference;

import com.notraius.database.entities.lease.reference.amenity.Amenity;
import com.notraius.database.entities.lease.reference.status.Duration;
import com.notraius.database.entities.lease.reference.status.Status;
import com.notraius.database.entities.lease.reference.status.Type;
import com.notraius.modules.core.config.reference.dto.response.AmenityResponse;
import com.notraius.modules.core.config.reference.dto.response.DurationResponse;
import com.notraius.modules.core.config.reference.dto.response.TypeResponse;
import org.springframework.stereotype.Component;

/**
 * Row → DTO for the reference catalogues. Pure projection: no lookups, no
 * defaults invented — a null relation stays a null field rather than becoming
 * a fabricated code, so a client can tell "no parent" from "parent unknown".
 */
@Component
public class ReferenceMapper {

    public TypeResponse toResponse(Type type) {
        return new TypeResponse(
                type.getId(),
                type.getEntity() != null ? type.getEntity().getCode() : null,
                type.getParent() != null ? type.getParent().getId() : null,
                type.getCode(),
                type.getLabel(),
                type.getDescription(),
                statusCode(type.getStatus()),
                type.getCreatedBy(),
                type.getCreatedAt(),
                type.getUpdatedAt()
        );
    }

    public DurationResponse toResponse(Duration duration) {
        Type type = duration.getType();
        return new DurationResponse(
                duration.getId(),
                type != null ? type.getId() : null,
                type != null ? type.getCode() : null,
                // The unit's label lives on /types; the code is the reference.
                duration.getValue(),
                statusCode(duration.getStatus()),
                duration.getCreatedBy(),
                duration.getCreatedAt(),
                duration.getUpdatedAt()
        );
    }

    public AmenityResponse toResponse(Amenity amenity) {
        Type type = amenity.getType();
        return new AmenityResponse(
                amenity.getId(),
                type != null ? type.getId() : null,
                type != null ? type.getCode() : null,
                amenity.getDataType() != null ? amenity.getDataType().name() : null,
                amenity.getCode(),
                amenity.getLabel(),
                amenity.getDescription(),
                statusCode(amenity.getStatus()),
                amenity.getProposer(),
                amenity.getCreatedBy(),
                amenity.getCreatedAt(),
                amenity.getUpdatedAt()
        );
    }

    private String statusCode(Status status) {
        return status != null ? status.getCode() : null;
    }
}
