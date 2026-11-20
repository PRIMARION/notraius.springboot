package com.notraius.modules.core.config.reference.dto.request.duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Mint a duration (user tier — durations are user-created so a personal user
 * does not queue behind an admin for every calendar combination).
 * <p>
 * The pair (type, value) is the identity: HOUR + 10 created twice is one row
 * and the second creation is refused. The value is a positive integer — a
 * night of zero hours prices nothing.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DurationCreateRequest {

    /** The unit kind — a live type under the DURATION_UNIT entity. */
    @NotNull
    private UUID typeId;

    /** How many of that unit. At least one. */
    @NotNull
    @Min(1)
    private Integer value;
}
