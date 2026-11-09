package com.notraius.modules.config.policies.mapper;

import com.notraius.database.entities.config.Policy;
import com.notraius.database.entities.reference.Type;
import com.notraius.modules.config.policies.dto.response.PolicyResponse;
import org.springframework.stereotype.Component;

/** Row → DTO for a policy. Pure projection, no invented defaults. */
@Component
public class PolicyMapper {

    public PolicyResponse toResponse(Policy policy) {
        Type type = policy.getType();
        return new PolicyResponse(
                policy.getId(),
                policy.getEntity() != null ? policy.getEntity().getCode() : null,
                type != null ? type.getId() : null,
                type != null ? type.getCode() : null,
                policy.getCode(),
                policy.getTitle(),
                policy.getDescription(),
                policy.getMinimum(),
                policy.getMaximum(),
                policy.isMandatory(),
                policy.getUnit() != null ? policy.getUnit().name() : null,
                policy.getVersion(),
                policy.getStateId(),
                policy.getStatus() != null ? policy.getStatus().getCode() : null,
                policy.getCreatedAt(),
                policy.getUpdatedAt()
        );
    }
}
