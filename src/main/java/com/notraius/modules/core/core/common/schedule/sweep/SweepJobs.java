package com.notraius.modules.core.core.common.schedule.sweep;

/**
 * The scheduled sweeps' stable names — the ledger's key, and the only thing
 * that ties a cursor to the worklist it belongs to.
 * <p>
 * Constants rather than string literals at each call site: the value is stored
 * in {@code notraius.sweep_cursors.job} and seeded by migration V36, so a typo
 * in one service would silently start a second, cursor-less sweep that always
 * restarts from the head — the exact failure the ledger exists to prevent.
 * <p>
 * Renaming one of these is a migration, not a refactor.
 */
public final class SweepJobs {

    private SweepJobs() {
    }

    /** PENDING leases past the staleness window — silence never keeps money (6.1). */
    public static final String LEASE_EXPIRY = "lease-expiry";

    /** Unanswered notices past their window (6.4). */
    public static final String LEASE_VIGIL_NOTICES = "lease-vigil-notices";

    /** Accepted notices whose grace has run out (6.5). */
    public static final String LEASE_VIGIL_ANSWERED_NOTICES = "lease-vigil-answered-notices";

    /** Due breakdowns whose deadline has passed (6.3). */
    public static final String LEASE_VIGIL_BILLS = "lease-vigil-bills";

    /** Open settlements past their deadline (6.3). */
    public static final String LEASE_VIGIL_SETTLEMENTS = "lease-vigil-settlements";

    /** ACTIVE tenancies whose validity has ended (6.5). */
    public static final String LEASE_VIGIL_ENDINGS = "lease-vigil-endings";

    /** Open-ended ACTIVE tenancies whose calendar needs rolling (6.2). */
    public static final String LEASE_VIGIL_HORIZONS = "lease-vigil-horizons";

    /** Issued RECONVERSION notices waiting to be dissolved (6.6). */
    public static final String LEASE_VIGIL_RECONVERSIONS = "lease-vigil-reconversions";

    /** Requested visits past their confirmation window (5.3). */
    public static final String VISIT_EXPIRY_REQUEST = "visit-expiry-request";

    /** Confirmed visits past their confirmation window (5.3). */
    public static final String VISIT_EXPIRY_CONFIRMATION = "visit-expiry-confirmation";

    /** Claimed visits whose countersignature never came (5.3). */
    public static final String VISIT_OUTCOME_CLAIMS = "visit-outcome-claims";

    /** Confirmed visits past their planned end with no claim (5.3). */
    public static final String VISIT_OUTCOME_SILENT = "visit-outcome-silent";

    /** Confirmed starts whose assessment hour ran out (5.3). */
    public static final String VISIT_OUTCOME_HOUR = "visit-outcome-hour";
}
