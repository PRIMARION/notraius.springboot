package com.notraius.modules.core.config.policies.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Edit a policy's statement (admin tier): its text and its bounds.
 * <p>
 * Not here, deliberately:
 * <ul>
 *   <li>{@code code}, {@code entity} and {@code type} — they are the standard's
 *       identity and what it governs, fixed at birth;</li>
 *   <li>{@code unit} — changing UNITS to AMOUNT would reinterpret every stored
 *       value as the other kind of thing;</li>
 *   <li>{@code mandatory} — flipping it is its own act, because it backfills
 *       every applicable configuration at the minimum, so it travels through
 *       {@code PUT /policies/{id}/mandatory}.</li>
 * </ul>
 * A null field means "leave unchanged"; tightening the bounds is legitimate and
 * grandfathers out-of-range stored values with a review flag rather than
 * clamping them.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PolicyUpdateRequest {

    @Size(max = 100)
    @Pattern(regexp = ".*\\S.*", message = "title must not be blank")
    private String title;

    @Size(max = 5000)
    private String description;

    @PositiveOrZero
    private BigDecimal min;

    @PositiveOrZero
    private BigDecimal max;
}
