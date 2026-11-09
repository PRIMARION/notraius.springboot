package com.notraius.modules.lease.state.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * A dispute's opening or its resolution (PLAN 6.7).
 * <p>
 * The reason is the ticket's face: it is logged with the freeze, and the
 * resolution's note is logged with the recalculation, so the logs hold both the
 * pre-dispute and the post-resolution schedules.
 */
@Getter
@Setter
public class LeaseDisputeRequest {

    @NotBlank(message = "A dispute must state its reason")
    @Size(max = 4000, message = "A dispute's reason cannot exceed 4000 characters")
    private String reason;
}
