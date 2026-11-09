package com.notraius.modules.config.reference.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Correct a duration's value (admin tier).
 * <p>
 * Only the value: the unit type is half of the row's identity, so a duration
 * bound to a different unit is a different duration — delete the unused row
 * and create the right one. And a duration already bound to a live pricing is
 * retired rather than edited, because changing its value silently reprices
 * everything that references it; the service refuses that edit.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DurationUpdateRequest {

    @NotNull
    @Min(1)
    private Integer value;
}
