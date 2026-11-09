package com.notraius.modules.lease.state.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A repair listed against a move-out report (PLAN 6.7).
 * <p>
 * The payer is <b>declared</b>, never derived from authorship: the landlord
 * authors repairs charged to the tenant just as readily as repairs charged to
 * himself, and this field is the only figure that tells them apart. A charge
 * without a payer (or a payer without a charge) refuses loudly.
 */
@Getter
@Setter
public class LeaseRepairRequest {

    /** The AFTER_MOVE_OUT condition this repair was listed against. */
    private UUID conditionId;

    @NotBlank(message = "A repair must have a title")
    @Size(max = 255, message = "A repair's title cannot exceed 255 characters")
    private String title;

    @Size(max = 4000, message = "A repair's description cannot exceed 4000 characters")
    private String description;

    /** The charged figure; null or zero for a repair the landlord owes himself. */
    private BigDecimal chargeAmount;

    /** Who pays the charge: the tenant or the landlord. Required with a charge. */
    private UUID payerId;
}
