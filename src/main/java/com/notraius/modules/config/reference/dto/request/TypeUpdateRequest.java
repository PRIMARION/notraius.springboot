package com.notraius.modules.config.reference.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Edit a type's human-facing fields (admin tier).
 * <p>
 * Only the label and the description: the {@code code} is a behavioural edit
 * and not a rename (PLAN.md Part 1), so it is immutable once the row exists —
 * retire and recreate is the path for a different code. The parent moves
 * through its own endpoint because a reparent is a structural act with a
 * cycle check, not a field edit.
 * <p>
 * A null field means "leave unchanged".
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TypeUpdateRequest {

    /** Null = unchanged; a blank label is never a valid label. */
    @Size(max = 100)
    @Pattern(regexp = ".*\\S.*", message = "label must not be blank")
    private String label;

    @Size(max = 2000)
    private String description;
}
