package com.notraius.modules.lease.lease.agreement.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An imported tenancy's declaration (PLAN 6.6).
 * <p>
 * <b>Case one names the debts</b> — every month the tenant owes, with the period
 * it covers and the rent that was real in the selected pricing version.
 * <b>Case two names the holdings</b> — the amounts already advanced or secured in
 * the physical world, which the landlord funds into custody himself. Both cases
 * may ride one lease: the reconversion clock paces the backlog while the declared
 * holdings stand as the deposit truth.
 * <p>
 * At least one of the two lists must be non-empty: an import that states neither
 * is a lease with no imported truth, and refuses loudly.
 */
@Getter
@Setter
public class LeaseImportRequest {

    @NotNull(message = "An imported lease needs the pricing its rates were real in")
    private UUID pricingId;

    @NotNull(message = "An imported lease needs the tenant it binds")
    private UUID tenantId;

    /** When the paper tenancy began; defaults to now. */
    private Instant fromDate;

    /** The paper tenancy's end, when it has one. */
    private Instant toDate;

    private UUID termsId;

    @Valid
    private List<DebtLine> debts = new ArrayList<>();

    @Valid
    private List<HoldingLine> holdings = new ArrayList<>();

    /** One month the tenant already owes. */
    @Getter
    @Setter
    public static class DebtLine {

        @NotNull(message = "A declared debt must name the period it covers")
        private Instant periodStart;

        @NotNull(message = "A declared debt must name the period it covers")
        private Instant periodEnd;

        @NotNull(message = "A declared debt must state its frozen amount")
        private BigDecimal amount;
    }

    /** One deposit truth already standing in the physical world. */
    @Getter
    @Setter
    public static class HoldingLine {

        /** The standard's policy code (ADVANCE, SECURITY, ELECTRICITY, …). */
        private String policyCode;

        /** Whole units held, for a UNITS-valued standard. */
        private BigDecimal units;

        /** The figure held, for an AMOUNT-valued standard. */
        private BigDecimal amount;
    }
}
