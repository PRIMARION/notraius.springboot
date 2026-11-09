package com.notraius.modules.asset.terms.mapper;

import com.notraius.database.entities.asset.PropertyPolicyValue;
import com.notraius.database.entities.asset.PropertyRuleSelection;
import com.notraius.database.entities.config.Policy;
import com.notraius.database.entities.config.Rule;
import com.notraius.database.entities.reference.Validity;
import com.notraius.modules.asset.terms.dto.response.PolicyValueResponse;
import com.notraius.modules.asset.terms.dto.response.RuleSelectionResponse;
import org.springframework.stereotype.Component;

/** Row → DTO for the property's terms: its values and its rule selections. */
@Component
public class TermsMapper {

    public PolicyValueResponse toValueResponse(PropertyPolicyValue value) {
        Policy policy = value.getPolicy();
        return new PolicyValueResponse(
                value.getId(),
                value.getPricing() != null ? value.getPricing().getId() : null,
                policy != null ? policy.getId() : null,
                policy != null ? policy.getCode() : null,
                // Title, unit, bounds and mandatory are /policies' row, not this one's.
                value.getValue(),
                value.getStatus() != null ? value.getStatus().getCode() : null,
                value.getVersion(),
                value.getCreatedAt(),
                value.getUpdatedAt()
        );
    }

    public RuleSelectionResponse toSelectionResponse(PropertyRuleSelection selection, String effectiveStatusCode) {
        Rule rule = selection.getRule();
        Validity validity = selection.getValidity();
        return new RuleSelectionResponse(
                selection.getId(),
                selection.getProperty() != null ? selection.getProperty().getId() : null,
                rule != null ? rule.getId() : null,
                // The rule's content and visibility are /rules' answer.
                validity != null ? validity.getFromDate() : null,
                validity != null ? validity.getToDate() : null,
                effectiveStatusCode,
                // The act beside the verdict: a snapshot must not freeze the clock.
                // A selection's act lives on its window (the validity's own status).
                validity != null && validity.getStatus() != null
                        ? validity.getStatus().getCode() : null,
                selection.getVersion(),
                selection.getCreatedAt()
        );
    }
}
