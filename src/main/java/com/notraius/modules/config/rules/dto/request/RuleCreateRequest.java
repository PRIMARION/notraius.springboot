package com.notraius.modules.config.rules.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Create a rule — any authenticated user (PLAN.md 3.1).
 * <p>
 * The visibility is chosen here, at birth, and is the rule's whole governance
 * shape: PUBLIC means every authenticated user may read and select it, PRIVATE
 * means its creator alone (with the property-listing and staff-moderation
 * exceptions). There is no default — the caller states which world the rule
 * lives in.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RuleCreateRequest {

    @NotBlank
    @Size(max = 100)
    private String title;

    /** A rule with nothing to say is not a rule, so the body is required. */
    @NotBlank
    @Size(max = 5000)
    private String description;

    /** PUBLIC or PRIVATE. */
    @NotBlank
    @Size(max = 12)
    private String visibility;
}
