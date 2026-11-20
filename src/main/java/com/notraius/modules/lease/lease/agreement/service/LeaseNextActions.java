package com.notraius.modules.lease.lease.agreement.service;

import com.notraius.database.entities.lease.lease.records.Lease;
import com.notraius.modules.core.core.common.constants.NotraiusStatuses;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Locale;

/**
 * What this caller may do with this lease, right now — the tenancy machine's own
 * answer, derived from the row (and whether its funding landed) alone.
 * <p>
 * The PENDING lease is where a party is stuck without this: the tenant has paid
 * and does not know whether the money still waits on its own verification (an
 * import) or on the owner's word (a native tenancy), and the owner sees a row
 * holding someone's deposit without knowing whether confirming it is theirs to
 * do yet. The status alone cannot say that — {@code fundingResolved} is the
 * missing half, and the read already carries it.
 * <p>
 * Same three laws as the visit's hint: a hint is never a gate (every endpoint
 * re-checks), nothing is invented that the row cannot prove (the condition
 * stages live in their own sub-resource, and the owner's KYC is the caller's
 * state, not the lease's), and each party is answered for itself.
 */
public final class LeaseNextActions {

    /** The beneficiary accepts the funded tenancy. */
    public static final String CONFIRM = "CONFIRM";
    /** Either party ends a PENDING tenancy before the commitment. */
    public static final String CANCEL = "CANCEL";
    /** The imported lease's tenant verifies its declared truth. */
    public static final String ACCEPT_IMPORT = "ACCEPT_IMPORT";
    /** The imported lease's tenant refuses it whole. */
    public static final String REFUSE_IMPORT = "REFUSE_IMPORT";
    /** The tenant reports the premises as received, before move-in. */
    public static final String REPORT_CONDITION = "REPORT_CONDITION";

    private LeaseNextActions() {
    }

    /**
     * The acts this caller may take on this lease now; empty for a terminal
     * tenancy, a caller who is not a party, or a PENDING lease whose funding is
     * still unresolved in a way only the counterpart can clear.
     */
    public static List<String> of(Lease lease, UUID callerId, boolean fundingResolved) {
        if (lease == null || callerId == null) {
            return List.of();
        }
        String status = lease.getStatus() != null ? lease.getStatus().getCode() : null;
        if (status == null) {
            return List.of();
        }

        boolean tenant = callerId.equals(lease.getUserId());
        boolean owner = callerId.equals(lease.getOwnerId());
        if (!tenant && !owner) {
            return List.of();
        }

        boolean imported = NotraiusStatuses.LEASE_ORIGIN_IMPORT.equalsIgnoreCase(lease.getOrigin());
        List<String> actions = new ArrayList<>(3);

        switch (status.toUpperCase(java.util.Locale.ROOT)) {
            case NotraiusStatuses.LEASE_PENDING -> {
                if (imported) {
                    if (tenant) {
                        // Accepting is verification, so it waits for the custody
                        // to answer; refusing never does.
                        if (fundingResolved) {
                            actions.add(ACCEPT_IMPORT);
                        }
                        actions.add(REFUSE_IMPORT);
                    }
                    if (owner) {
                        actions.add(CANCEL);
                    }
                } else {
                    if (owner && fundingResolved) {
                        actions.add(CONFIRM);
                    }
                    actions.add(CANCEL);
                }
            }
            case NotraiusStatuses.LEASE_CONFIRMED -> {
                // Confirmed and checkable: the tenant reports the premises as
                // received, and the last move-in confirmation activates the lease.
                if (tenant) {
                    actions.add(REPORT_CONDITION);
                }
            }
            default -> {
                // ACTIVE's move-out report belongs to the settlement's own week,
                // which this row does not carry; the terminal statuses are the end.
            }
        }
        return List.copyOf(actions);
    }
}
