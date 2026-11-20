package com.notraius.modules.lease.lease.finance.dto.request;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

/**
 * A top-up's body (PLAN 6.3): how many whole units of the advance standard the
 * tenant restores. An AMOUNT-valued standard restores its one global split and
 * therefore accepts exactly one unit.
 */
@Getter
@Setter
public class LeaseTopUpRequest {

    @Min(value = 1, message = "A top-up must restore at least one whole unit")
    private int units = 1;
}
