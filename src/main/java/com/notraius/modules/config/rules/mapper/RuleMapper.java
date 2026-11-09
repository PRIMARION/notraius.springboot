package com.notraius.modules.config.rules.mapper;

import com.notraius.database.entities.config.Rule;
import com.notraius.modules.config.rules.dto.response.RuleResponse;
import org.springframework.stereotype.Component;

/** Row → DTO for a rule. Pure projection, no invented defaults. */
@Component
public class RuleMapper {

    public RuleResponse toResponse(Rule rule) {
        return new RuleResponse(
                rule.getId(),
                rule.getTitle(),
                rule.getDescription(),
                rule.getVisibility() != null ? rule.getVisibility().name() : null,
                rule.getVersion(),
                rule.getStatus() != null ? rule.getStatus().getCode() : null,
                rule.getCreatorId(),
                rule.getCreatedAt(),
                rule.getUpdatedAt()
        );
    }
}
