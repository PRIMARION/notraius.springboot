package com.notraius.modules.lease.asset.terms.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.Set;

/**
 * Set the owner's number against one admin standard, on one pricing. The
 * standard is the admin's; the number is the owner's; the pricing supplies the
 * unit (four MONTHS because the pricing is monthly).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PolicyValueSetRequest {

    @NotNull
    private UUID policyId;

    @NotNull
    private BigDecimal value;
}
