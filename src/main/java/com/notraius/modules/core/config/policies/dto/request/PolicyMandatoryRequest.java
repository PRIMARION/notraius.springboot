package com.notraius.modules.core.config.policies.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Flip a policy's mandatory flag (admin tier) — the compliance switch.
 * <p>
 * Making a standard mandatory means every configuration it governs must carry a
 * value before it can be published, so the flip backfills those configurations
 * at the policy's minimum instead of blocking publication by mere absence.
 * Existing live configurations are grandfathered (they are not unpublished);
 * they are flagged incomplete for their next edit. Exactly one case keeps the
 * refusal: a mandatory policy with no minimum has no default to give, so
 * publication waits on a human with the policy named.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PolicyMandatoryRequest {

    @NotNull
    private Boolean mandatory;
}
