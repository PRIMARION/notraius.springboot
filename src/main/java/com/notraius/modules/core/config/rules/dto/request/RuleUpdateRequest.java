package com.notraius.modules.core.config.rules.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Edit a rule's text (creator only).
 * <p>
 * Every accepted edit bumps the version and writes its before/after log entry
 * in the same transaction, so the previous truth survives: the row holds the
 * current version and the log chain holds every earlier one.
 * <p>
 * Visibility is deliberately absent — flipping it is its own act with its own
 * refusal (someone else's property may be relying on the rule), so it travels
 * through {@code PUT /rules/{id}/visibility} instead of riding a text edit.
 * A null field means "leave unchanged".
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RuleUpdateRequest {

    @Size(max = 100)
    @Pattern(regexp = ".*\\S.*", message = "title must not be blank")
    private String title;

    @Size(max = 5000)
    @Pattern(regexp = "(?s).*\\S.*", message = "description must not be blank")
    private String description;
}
