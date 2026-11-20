package com.notraius.modules.lease.lease.finance.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * The receiver's word that cash changed hands (PLAN 6.8).
 * <p>
 * The amount is required: the machine records what physically happened, and a
 * claim that does not complete the due figure leaves the bill unpaid while the
 * fact is logged. There is no receipt field here — documents ride the storage
 * module's own {@code (entity, reference)} pair.
 */
@Getter
@Setter
public class LeaseCashConfirmRequest {

    @NotNull(message = "A cash confirmation must state the amount received")
    @DecimalMin(value = "0.01", message = "A cash confirmation must be strictly positive")
    private BigDecimal amount;

    private String note;
}
