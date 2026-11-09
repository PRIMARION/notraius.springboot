package com.notraius.modules.lease.agreement.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * A condition report's body (PLAN 6.2/6.7).
 * <p>
 * The caller names the stage because the same row shape serves both ends of a
 * tenancy: the tenant's move-in report and the landlord's move-out report. The
 * stage is validated against the lease's own status and the caller's role, so a
 * body can never choose a door the caller may not walk through.
 */
@Getter
@Setter
public class LeaseConditionRequest {

    /** BEFORE_MOVE_IN (default) or AFTER_MOVE_OUT. */
    private String type;

    @NotBlank(message = "A condition report must say what it reports")
    @Size(max = 4000, message = "A condition report cannot exceed 4000 characters")
    private String notes;
}
