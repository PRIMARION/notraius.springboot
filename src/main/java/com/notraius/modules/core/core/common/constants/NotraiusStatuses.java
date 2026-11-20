package com.notraius.modules.core.core.common.constants;

import com.notraius.database.entities.asset.asset.Property;
import com.notraius.database.entities.lease.lease.records.Lease;
import com.notraius.database.entities.lease.reference.status.Status;
import com.notraius.database.entities.lease.visit.Visit;
import java.util.List;

/**
 * Status codes for the notraius reference catalogue. Each group is scoped to
 * its own {@code notraius.entities} row, so the same word never means two
 * things (the estate's entity-scoped status law).
 * <p>
 * Only the statuses the code compares against live here; the complete
 * vocabularies are seeded by {@code V25__reference_catalogue.sql}.
 */
public final class NotraiusStatuses {
    private NotraiusStatuses() {}

    /**
     * The terminal word every reference catalogue shares — a retired row stops
     * being offered while remaining readable for history.
     */
    public static final String RETIRED = "RETIRED";

    // ── Types ─────────────────────────────────────────────────────────────
    public static final String TYPE_ACTIVE = "ACTIVE";
    public static final String TYPE_RETIRED = RETIRED;

    // ── Durations ─────────────────────────────────────────────────────────
    public static final String DURATION_ACTIVE = "ACTIVE";
    public static final String DURATION_RETIRED = RETIRED;

    // ── Amenities ─────────────────────────────────────────────────────────
    public static final String AMENITY_PENDING = "PENDING";
    public static final String AMENITY_ACTIVE = "ACTIVE";
    public static final String AMENITY_REJECTED = "REJECTED";
    public static final String AMENITY_CANCELLED = "CANCELLED";
    public static final String AMENITY_DISABLED = "DISABLED";
    public static final String AMENITY_RETIRED = RETIRED;

    // ── Rules (config 3.1) ────────────────────────────────────────────────
    public static final String RULE_ACTIVE = "ACTIVE";
    public static final String RULE_DISABLED = "DISABLED";
    public static final String RULE_RETIRED = RETIRED;

    // ── Policies (config 3.2) ─────────────────────────────────────────────
    public static final String POLICY_ENABLED = "ENABLED";
    public static final String POLICY_DISABLED = "DISABLED";

    // ── Properties (asset 4.1) ────────────────────────────────────────────
    public static final String PROPERTY_DRAFT = "DRAFT";
    public static final String PROPERTY_PUBLISHED = "PUBLISHED";
    /** The owner's own unpublish. */
    public static final String PROPERTY_PAUSED = "PAUSED";
    /** Staff moderation: the owner cannot lift it alone. */
    public static final String PROPERTY_SUSPENDED = "SUSPENDED";
    public static final String PROPERTY_DELETED = "DELETED";
    /** A live tenancy occupies it: no viewings, no new leases (PLAN 6.0). */
    public static final String PROPERTY_LEASED = "LEASED";
    /** Vacated and deliberately dark until the owner's "make it available" click. */
    public static final String PROPERTY_DISABLED = "DISABLED";

    // ── Pricings (asset 4.2) ──────────────────────────────────────────────
    public static final String PRICING_LIVE = "LIVE";
    public static final String PRICING_PAUSED = "PAUSED";
    /**
     * The clock's verdict, derived at read time from the window — never a
     * stored status (a job and a request must not be able to disagree).
     */
    public static final String PRICING_EXPIRED = "EXPIRED";

    // ── Property policy values (asset 4.3) ────────────────────────────────
    public static final String POLICY_VALUE_ACTIVE = "ACTIVE";
    public static final String POLICY_VALUE_PAUSED = "PAUSED";
    public static final String POLICY_VALUE_FLAGGED = "FLAGGED";

    // ── Property amenity holdings (asset 4.1 / config 3.3) ─────────────────
    public static final String PROPERTY_AMENITY_ACTIVE = "ACTIVE";
    /** The owner's own switch — a different act from the catalogue's. */
    public static final String PROPERTY_AMENITY_DISABLED = "DISABLED";
    /** The catalogue switched it off underneath the property. */
    public static final String PROPERTY_AMENITY_SUPPRESSED = "SUPPRESSED";

    // ── Property rule selections (asset 4.1) ──────────────────────────────
    public static final String RULE_SELECTION_ACTIVE = "ACTIVE";
    /**
     * Derived, never stored: a selection whose window has ended reads EXPIRED,
     * exactly as a pricing does. The stored status says the selection was made;
     * the clock says whether it still applies.
     */
    public static final String RULE_SELECTION_EXPIRED = "EXPIRED";

    // ── Visit pricings (visit 4.1 / PLAN 5.1) ─────────────────────────────
    public static final String VISIT_PRICING_ACTIVE = "ACTIVE";
    public static final String VISIT_PRICING_PAUSED = "PAUSED";

    // ── Visits (PLAN 5.1–5.5) ─────────────────────────────────────────────
    /**
     * The row a funding call left without an answer. Not an error state: pay may
     * have taken the money, so the visit stands until the sweep re-reads pay's
     * truth instead of guessing (PLAN 5.1).
     */
    public static final String VISIT_UNPAID = "UNPAID";
    public static final String VISIT_REQUESTED = "REQUESTED";
    public static final String VISIT_ASSIGNED = "ASSIGNED";
    public static final String VISIT_PLANNING = "PLANNING";
    public static final String VISIT_CONFIRMED = "CONFIRMED";
    public static final String VISIT_STARTED = "STARTED";
    public static final String VISIT_COMPLETED = "COMPLETED";
    public static final String VISIT_EXPIRED = "EXPIRED";
    public static final String VISIT_CANCELLED = "CANCELLED";

    // ── Leases (PLAN 6.0–6.9) ─────────────────────────────────────────────
    /** Funded intent: neither party is ready yet. */
    public static final String LEASE_PENDING = "PENDING";
    /** The owner committed; the move-in conditions stage is due (6.2). */
    public static final String LEASE_CONFIRMED = "CONFIRMED";
    /** The tenancy lives; the calendar bills it. */
    public static final String LEASE_ACTIVE = "ACTIVE";
    public static final String LEASE_COMPLETED = "COMPLETED";
    /** The pre-commitment exit only — never a mid-life one (6.0). */
    public static final String LEASE_CANCELLED = "CANCELLED";
    /** Silence ended it and the whole matrix went back (6.1). */
    public static final String LEASE_EXPIRED = "EXPIRED";
    /** A ticket froze the machine; only humans move it now (6.7). */
    public static final String LEASE_DISPUTED = "DISPUTED";

    // ── Lease deposits + their split ledger (PLAN 6.3/6.4) ────────────────
    public static final String LEASE_DEPOSIT_HELD = "HELD";
    /** Consumed: the advance paid the rent it was parked for. */
    public static final String LEASE_DEPOSIT_RELEASED = "RELEASED";
    /** Refunded to the tenant at settlement. */
    public static final String LEASE_DEPOSIT_RETURNED = "RETURNED";

    /** A split has exactly two fates, and HELD before either (6.0 atomicity). */
    public static final String LEASE_SPLIT_HELD = "HELD";
    public static final String LEASE_SPLIT_RELEASED = "RELEASED";
    public static final String LEASE_SPLIT_REFUNDED = "REFUNDED";

    // ── Lease breakdowns (PLAN 6.3) ───────────────────────────────────────
    public static final String LEASE_BREAKDOWN_PENDING = "PENDING";
    public static final String LEASE_BREAKDOWN_SETTLED = "SETTLED";
    public static final String LEASE_BREAKDOWN_DEPOSITED = "DEPOSITED";
    public static final String LEASE_BREAKDOWN_OVERDUE = "OVERDUE";
    /** Cut away by a reeled horizon before any money stood against it (6.5). */
    public static final String LEASE_BREAKDOWN_CANCELLED = "CANCELLED";

    // ── Lease top-ups (PLAN 6.3) ──────────────────────────────────────────
    public static final String LEASE_TOPUP_PENDING = "PENDING";
    public static final String LEASE_TOPUP_FUNDED = "FUNDED";
    public static final String LEASE_TOPUP_FAILED = "FAILED";

    // ── Lease notice clocks (PLAN 6.5) — the validity row's own words ─────
    public static final String LEASE_NOTICE_ISSUED = "ISSUED";
    public static final String LEASE_NOTICE_ACCEPTED = "ACCEPTED";
    public static final String LEASE_NOTICE_REFUSED = "REFUSED";
    public static final String LEASE_NOTICE_ESCALATED = "ESCALATED";
    public static final String LEASE_NOTICE_CANCELLED = "CANCELLED";
    public static final String LEASE_NOTICE_SATISFIED = "SATISFIED";
    public static final String LEASE_NOTICE_EXPIRED = "EXPIRED";

    // ── Lease conditions and repairs (PLAN 6.2/6.7) ───────────────────────
    public static final String LEASE_CONDITION_BEFORE_MOVE_IN = "BEFORE_MOVE_IN";
    public static final String LEASE_CONDITION_AFTER_MOVE_OUT = "AFTER_MOVE_OUT";

    // ── Lease origins (PLAN 6.6) ──────────────────────────────────────────
    public static final String LEASE_ORIGIN_NATIVE = "NATIVE";
    public static final String LEASE_ORIGIN_IMPORT = "IMPORT";

    // ── Lease notice types (PLAN 6.5/6.6) ─────────────────────────────────
    public static final String LEASE_NOTICE_DEPOSIT = "DEPOSIT";
    public static final String LEASE_NOTICE_VACATION = "VACATION";
    public static final String LEASE_NOTICE_RECONVERSION = "RECONVERSION";

    // ── Lease deposit types / breakdown types (PLAN 6.2–6.7) ──────────────
    public static final String LEASE_DEPOSIT_ADVANCE = "ADVANCE";
    public static final String LEASE_BREAKDOWN_INSTALLMENT = "INSTALLMENT";
    public static final String LEASE_BREAKDOWN_SETTLEMENT = "SETTLEMENT";
    public static final String LEASE_BREAKDOWN_REPAIR = "REPAIR";

    /** A condition report's own response words — never confused with a notice's. */
    public static final String LEASE_RESPONSE_ACCEPT = "ACCEPT";
    public static final String LEASE_RESPONSE_REFUSE = "REFUSE";
    public static final String LEASE_RESPONSE_CANCEL = "CANCELLED";
    /** Closed by the balance itself, never by a click (6.5's reading of silence). */
    public static final String LEASE_RESPONSE_SATISFIED = "SATISFIED";

    // ── Lease conditions + repairs (PLAN 6.2/6.7) ─────────────────────────
    public static final String LEASE_CONDITION_PENDING = "PENDING";
    public static final String LEASE_CONDITION_CONFIRMED = "CONFIRMED";

    // ── Lease settlements (PLAN 6.7) ──────────────────────────────────────
    public static final String LEASE_SETTLEMENT_OPEN = "OPEN";
    public static final String LEASE_SETTLEMENT_OVERDUE = "OVERDUE";
    public static final String LEASE_SETTLEMENT_CONFIRMED = "CONFIRMED";

    /** The statuses a lease still owes money or occupancy for (6.0's in-use law). */
    public static final java.util.List<String> LEASE_LIVE_STATUSES = java.util.List.of(
            LEASE_PENDING, LEASE_CONFIRMED, LEASE_ACTIVE, LEASE_DISPUTED);
}
