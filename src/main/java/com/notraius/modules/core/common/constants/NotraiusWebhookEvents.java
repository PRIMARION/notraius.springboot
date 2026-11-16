package com.notraius.modules.core.common.constants;

/**
 * The webhook EVENT vocabulary notarius's dispatchers speak — every
 * {@code (family, word)} pair the dispatch sites can emit: the visit
 * lifecycle (VisitService + VisitLedgerService, terminal words riding the
 * status code), the lease lifecycle (LeaseAgreementService / LeaseState
 * / LeaseFinance through LeaseAnnouncer — the announcer takes the dotted
 * activity and dispatches on its family), and the pricing window
 * (PricingService).
 *
 * <p>Two word sources deserve their note: the lease TERMINAL words are the
 * bare status words ({@code CANCELLED}/{@code EXPIRED}/{@code COMPLETED} —
 * the end() pass-through), and the notice-ISSUED word is the notice
 * typeCode itself ({@code DEPOSIT}/{@code VACATION}/{@code RECONVERSION}).
 *
 * <p>NEW-52 law: {@code PermissionSeeder} scans this catalogue (via
 * {@code app.activities.event-classes}) and seeds one permission row per
 * pair with the sentinel SYSTEM|GTE policy, so a subscriber has a row to
 * bind a webhook to and {@code WebhookService.dispatchEvent} resolves it.
 * The catalogue is the contract — a dispatcher word missing here ships a
 * webhook nobody can subscribe to; the drift test holds the line.
 */
public final class NotraiusWebhookEvents {
    private NotraiusWebhookEvents() {}

    // ── Visits — VisitService + VisitLedgerService.announceTerminal ───
    public static final class Visits {
        private Visits() {}
        public static final String REQUESTED         = "VISITS.REQUESTED";
        public static final String REQUEST_ADOPTED   = "VISITS.REQUEST_ADOPTED";
        public static final String ASSIGNED          = "VISITS.ASSIGNED";
        public static final String REASSIGNED        = "VISITS.REASSIGNED";
        public static final String ACCEPTED          = "VISITS.ACCEPTED";
        public static final String REJECTED          = "VISITS.REJECTED";
        public static final String DECLINED          = "VISITS.DECLINED";
        public static final String TIME_PROPOSED     = "VISITS.TIME_PROPOSED";
        public static final String TIME_CONFIRMED    = "VISITS.TIME_CONFIRMED";
        public static final String RESCHEDULED       = "VISITS.RESCHEDULED";
        public static final String STARTED           = "VISITS.STARTED";
        public static final String START_CONFIRMED   = "VISITS.START_CONFIRMED";
        // Terminal words — VisitLedgerService dispatches the STATUS word
        // (VISIT_CANCELLED/VISIT_EXPIRED/VISIT_COMPLETED → the bare word).
        public static final String CANCELLED         = "VISITS.CANCELLED";
        public static final String EXPIRED           = "VISITS.EXPIRED";
        public static final String COMPLETED         = "VISITS.COMPLETED";
    }

    // ── Leases — LeaseAnnouncer (family of the dotted activity passed in) ─
    public static final class Leases {
        private Leases() {}
        public static final String REQUESTED             = "LEASES.REQUESTED";
        public static final String FUNDING_ADOPTED       = "LEASES.FUNDING_ADOPTED";
        public static final String CONFIRMED             = "LEASES.CONFIRMED";
        public static final String ACTIVATED             = "LEASES.ACTIVATED";
        // Terminal words — LeaseAgreementService.end() passes the status
        // word straight to the announcer.
        public static final String CANCELLED             = "LEASES.CANCELLED";
        public static final String EXPIRED               = "LEASES.EXPIRED";
        public static final String COMPLETED             = "LEASES.COMPLETED";
        public static final String CONDITION_REPORTED    = "LEASES.CONDITION_REPORTED";
        public static final String CONDITION_CONFIRMED   = "LEASES.CONDITION_CONFIRMED";
        public static final String INSTALLMENT_OVERDUE   = "LEASES.INSTALLMENT_OVERDUE";
        public static final String INSTALLMENT_PAID      = "LEASES.INSTALLMENT_PAID";
        public static final String CASH_CONFIRMED        = "LEASES.CASH_CONFIRMED";
        public static final String CASH_SHORT            = "LEASES.CASH_SHORT";
        public static final String TOPUP_FUNDED          = "LEASES.TOPUP_FUNDED";
        public static final String RECONVERSION_DISSOLVED = "LEASES.RECONVERSION_DISSOLVED";
        public static final String NOTICE_ACCEPTED       = "LEASES.NOTICE_ACCEPTED";
        public static final String NOTICE_REFUSED        = "LEASES.NOTICE_REFUSED";
        public static final String NOTICE_CANCELLED      = "LEASES.NOTICE_CANCELLED";
        public static final String NOTICE_ESCALATED      = "LEASES.NOTICE_ESCALATED";
        public static final String NOTICE_SATISFIED      = "LEASES.NOTICE_SATISFIED";
        public static final String SETTLEMENT_BORN       = "LEASES.SETTLEMENT_BORN";
        public static final String SETTLEMENT_CONFIRMED  = "LEASES.SETTLEMENT_CONFIRMED";
        public static final String IMPORT_DECLARED       = "LEASES.IMPORT_DECLARED";
        public static final String IMPORT_ACCEPTED       = "LEASES.IMPORT_ACCEPTED";
        public static final String IMPORT_REFUSED        = "LEASES.IMPORT_REFUSED";
        // The notice-ISSUED word is the notice typeCode itself (issue()
        // passes typeCode through as the event word).
        public static final String DEPOSIT               = "LEASES.DEPOSIT";
        public static final String VACATION              = "LEASES.VACATION";
        public static final String RECONVERSION          = "LEASES.RECONVERSION";
    }

    // ── Pricing — PricingService pricing-window lifecycle ─────────────
    public static final class Pricing {
        private Pricing() {}
        public static final String CREATED       = "PRICING.CREATED";
        public static final String UPDATED       = "PRICING.UPDATED";
        public static final String WINDOW_ISSUED = "PRICING.WINDOW_ISSUED";
        public static final String PAUSED        = "PRICING.PAUSED";
        public static final String RESUMED       = "PRICING.RESUMED";
        public static final String DELETED       = "PRICING.DELETED";
    }
}
