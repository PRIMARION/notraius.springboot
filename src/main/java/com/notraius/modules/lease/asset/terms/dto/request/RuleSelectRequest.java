package com.notraius.modules.lease.asset.terms.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Select a rule for a property with its own validity window: does this rule
 * apply from now to then, or permanently? The pool is only ever "my own rules,
 * plus public rules".
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RuleSelectRequest {

    @NotNull
    private UUID ruleId;

    /** Null means "from now". */
    private Instant fromDate;

    /** Null means permanent. */
    private Instant toDate;
}
