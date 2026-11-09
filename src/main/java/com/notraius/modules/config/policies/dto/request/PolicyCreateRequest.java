package com.notraius.modules.config.policies.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Define a policy (admin tier). A policy is created enabled or not at all —
 * there is no user-visible draft state.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PolicyCreateRequest {

    /** The namespace the standard lives in. */
    @NotBlank
    @Size(max = 50)
    private String entityCode;

    /** What kind of thing it governs (INSTALLMENT for ADVANCE, …). */
    @NotNull
    private UUID typeId;

    /** Stable identity, unique inside the entity. Normalised to upper case. */
    @NotBlank
    @Size(max = 50)
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]*$",
            message = "code must start with a letter and contain only letters, digits and underscores")
    private String code;

    @NotBlank
    @Size(max = 100)
    private String title;

    @Size(max = 5000)
    private String description;

    /** Lower bound; null means unbounded below. */
    @PositiveOrZero
    private BigDecimal min;

    /** Upper bound; null means unbounded above. */
    @PositiveOrZero
    private BigDecimal max;

    /** Mandatory standards must carry a value before a configuration publishes. */
    private boolean mandatory;

    /** UNITS (counted in the pricing's duration) or AMOUNT (money). */
    @NotBlank
    @Size(max = 12)
    private String unit;

    /** Geographic scope; null is the stateless fallback. */
    private UUID stateId;
}
