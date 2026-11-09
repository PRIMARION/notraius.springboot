package com.notraius.modules.lease.agreement.mapper;

import com.notraius.database.entities.lease.LeaseCondition;
import com.notraius.modules.lease.agreement.dto.response.LeaseConditionResponse;
import org.springframework.stereotype.Component;

/** The condition report's read shape. */
@Component
public class LeaseConditionMapper {

    public LeaseConditionResponse toResponse(LeaseCondition condition) {
        LeaseConditionResponse response = new LeaseConditionResponse();
        response.setId(condition.getId());
        response.setLeaseId(condition.getLease() != null ? condition.getLease().getId() : null);
        response.setType(condition.getType() != null ? condition.getType().getCode() : null);
        response.setStatus(condition.getStatus() != null ? condition.getStatus().getCode() : null);
        response.setNotes(condition.getNotes());
        response.setCreatedBy(condition.getCreatedBy());
        response.setVersion(condition.getVersion());
        response.setCreatedAt(condition.getCreatedAt());
        return response;
    }
}
