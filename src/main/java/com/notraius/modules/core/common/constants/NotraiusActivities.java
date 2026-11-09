package com.notraius.modules.core.common.constants;

/**
 * Notraius activity vocabulary — families addressable by the shared event
 * engine and webhook dispatcher (the part before the first dot). The domain
 * grows here as the notraius modules materialize; the class is the
 * registration anchor referenced by {@code app.activities.classes} in
 * application.yaml.
 * <p>
 * <b>One-pair law.</b> {@code ActivityBootstrapper} derives the activity rows
 * from these constants and {@code PermissionSeeder} derives the permission
 * rows from the live {@code @authz.authority(...)} declarations. One
 * {@code (activity, operation)} pair therefore carries exactly ONE policy
 * across the whole host — which is why each catalogue below owns its own
 * family instead of sharing one {@code REFERENCE} family: type writes are
 * ADMIN-tier, duration creation is PERSONAL-tier and amenity proposals are
 * PERSONAL-tier, so a shared family plus a shared operation word would
 * declare three different policies on the same pair and refuse to boot.
 * <p>
 * <b>The families below ARE the live endpoint families.</b> They are the same
 * strings the controllers declare in {@code @authz.authority(...)}, verbatim,
 * because that string is the join key of the whole guard estate: the
 * permission row ({@code access.permissions}), the plan binding
 * ({@code subscription.plan_features}) and the rate rule
 * ({@code rate.limits}) all hang off it, and the subscription guard's
 * {@code UsageCounter} counts audit rows by the activity id it resolves from
 * it. Two spellings for one family ({@code LEASE} here, {@code LEASES} in an
 * endpoint) would leave the permission row and the audit row on different
 * activities — a plan limit that can never see its own usage. One domain, one
 * spelling: plural for the module families, exactly as the endpoints declare.
 */
public final class NotraiusActivities {
    private NotraiusActivities() {}

    /** The house-rule catalogue (config 3.1) — user-owned, versioned. */
    public static final String RULE = "RULE";
    /** The policy catalogue (config 3.2) — admin standards. */
    public static final String POLICY = "POLICY";

    /** The asset module's property rows and their amenity holdings (4.1). */
    public static final String PROPERTY = "PROPERTY";
    /** The asset module's pricings (4.2). */
    public static final String PRICING = "PRICING";
    /** The asset module's terms: the owner's numbers (4.3). */
    public static final String PROPERTY_POLICIES = "PROPERTY_POLICIES";
    /** The asset module's terms: the owner's rule selections (4.3). */
    public static final String PROPERTY_RULES = "PROPERTY_RULES";

    /** The lease module (Part 6). */
    public static final String LEASES = "LEASES";
    /** The visits module (PLAN 5.1–5.5). */
    public static final String VISITS = "VISITS";
    /** The owner's visit price list (5.1). */
    public static final String VISIT_PRICINGS = "VISIT_PRICINGS";
    // ── Activity families ────────────────────────────────────────────────
    /** The type catalogue (hierarchical, entity-bound). */
    public static final String TYPE = "TYPE";
    /** The duration catalogue (a unit type + how many). */
    public static final String DURATION = "DURATION";
    /** The moderated amenity catalogue. */
    public static final String AMENITY = "AMENITY";

    // ── Types (Part 1) ───────────────────────────────────────────────────
    public static final class Types {
        private Types() {}
        public static final String CREATE   = "TYPE.CREATE";
        public static final String UPDATE   = "TYPE.UPDATE";
        public static final String REPARENT = "TYPE.REPARENT";
        public static final String RETIRE   = "TYPE.RETIRE";
        public static final String DELETE   = "TYPE.DELETE";
        public static final String LIST     = "TYPE.LIST";
        public static final String GET      = "TYPE.GET";
    }

    // ── Policies (config 3.2) ────────────────────────────────────────────
    public static final class Policies {
        private Policies() {}
        public static final String CREATE    = "POLICY.CREATE";
        public static final String UPDATE    = "POLICY.UPDATE";
        /** Its own pair: flipping mandatory backfills values across the estate. */
        public static final String MANDATORY = "POLICY.MANDATORY";
        public static final String ENABLE    = "POLICY.ENABLE";
        public static final String DISABLE   = "POLICY.DISABLE";
        public static final String LIST      = "POLICY.LIST";
        public static final String GET       = "POLICY.GET";
    }

    // ── Rules (config 3.1) ───────────────────────────────────────────────
    public static final class Rules {
        private Rules() {}
        public static final String CREATE     = "RULE.CREATE";
        public static final String UPDATE     = "RULE.UPDATE";
        public static final String VISIBILITY = "RULE.VISIBILITY";
        public static final String DISABLE    = "RULE.DISABLE";
        public static final String ENABLE     = "RULE.ENABLE";
        public static final String RETIRE     = "RULE.RETIRE";
        public static final String LIST       = "RULE.LIST";
        public static final String GET        = "RULE.GET";
    }

    // ── Durations (Part 2) ───────────────────────────────────────────────
    public static final class Durations {
        private Durations() {}
        public static final String CREATE = "DURATION.CREATE";
        public static final String UPDATE = "DURATION.UPDATE";
        public static final String RETIRE = "DURATION.RETIRE";
        public static final String DELETE = "DURATION.DELETE";
        public static final String LIST   = "DURATION.LIST";
        public static final String GET    = "DURATION.GET";
    }

    // ── Amenities (Part 3.3) ─────────────────────────────────────────────
    public static final class Amenities {
        private Amenities() {}
        public static final String PROPOSE = "AMENITY.PROPOSE";
        public static final String CANCEL  = "AMENITY.CANCEL";
        public static final String CONFIRM = "AMENITY.CONFIRM";
        public static final String REJECT  = "AMENITY.REJECT";
        public static final String UPDATE  = "AMENITY.UPDATE";
        public static final String DISABLE = "AMENITY.DISABLE";
        public static final String ENABLE  = "AMENITY.ENABLE";
        public static final String RETIRE  = "AMENITY.RETIRE";
        public static final String DELETE  = "AMENITY.DELETE";
        public static final String LIST    = "AMENITY.LIST";
        public static final String GET     = "AMENITY.GET";
        /**
         * The moderation queue — every row that is not live, any status.
         * <p>
         * [one-pair law] It cannot ride {@link #LIST}: LIST is the selection
         * surface every personal user reads (ACTIVE only), whereas this is a
         * staff-only view of other people's proposals, so it declares its own
         * pair at the ADMIN tier instead of widening LIST.
         */
        public static final String LIST_SUBMISSIONS = "AMENITY.LIST_SUBMISSIONS";
    }

    // ── Properties (asset 4.1) ───────────────────────────────────────────
    public static final class Properties {
        private Properties() {}
        public static final String CREATE    = "PROPERTY.CREATE";
        public static final String UPDATE    = "PROPERTY.UPDATE";
        /**
         * Setting the anchors (location → state → currency) is its own act
         * because it happens exactly once in a property's whole life.
         */
        public static final String LOCATION    = "PROPERTY.LOCATION";
        public static final String PUBLISH     = "PROPERTY.PUBLISH";
        public static final String UNPUBLISH   = "PROPERTY.UNPUBLISH";
        public static final String SUSPEND     = "PROPERTY.SUSPEND";
        public static final String REINSTATE   = "PROPERTY.REINSTATE";
        public static final String DELETE      = "PROPERTY.DELETE";
        public static final String AMENITY_SET = "PROPERTY.AMENITY_SET";
        public static final String AMENITY_DISABLE = "PROPERTY.AMENITY_DISABLE";
        public static final String AMENITY_ENABLE  = "PROPERTY.AMENITY_ENABLE";
        public static final String AMENITY_DELETE  = "PROPERTY.AMENITY_DELETE";
        /** The market page: published properties, anyone signed in. */
        public static final String LIST        = "PROPERTY.LIST";
        /** The owner's own page: every status, the caller's rows only. */
        public static final String LIST_MINE   = "PROPERTY.LIST_MINE";
        public static final String GET         = "PROPERTY.GET";
    }

    // ── Pricings (asset 4.2) ─────────────────────────────────────────────
    public static final class Pricings {
        private Pricings() {}
        public static final String CREATE = "PRICING.CREATE";
        public static final String UPDATE = "PRICING.UPDATE";
        /**
         * Issuing a new validity against the same pricing — the ONLY way a
         * price answers again after its window ended. Its own pair because it
         * is the act that moves the version and reopens the market.
         */
        public static final String WINDOW = "PRICING.WINDOW";
        public static final String PAUSE  = "PRICING.PAUSE";
        public static final String RESUME = "PRICING.RESUME";
        public static final String DELETE = "PRICING.DELETE";
        public static final String LIST   = "PRICING.LIST";
        public static final String GET    = "PRICING.GET";
    }

    // ── Visits (PLAN 5.1–5.5) ────────────────────────────────────────────
    public static final class Visits {
        private Visits() {}
        /**
         * The paid request: one act that mints the visit row and funds it.
         * Its audit entry is the money's first history entry, so it carries the
         * payment the funding call returned.
         */
        public static final String REQUEST = "VISITS.REQUEST";
        /** The request was retried and found its own funding already in place. */
        public static final String REQUEST_ADOPT = "VISITS.REQUEST_ADOPT";
        public static final String GET      = "VISITS.GET";
        public static final String LIST_MINE = "VISITS.LIST_MINE";
        /** The agent's own page: visits assigned to the caller, any status. */
        public static final String LIST_ASSIGNED = "VISITS.LIST_ASSIGNED";
        /** A clock ran out and the visitor was refunded. */
        public static final String EXPIRE   = "VISITS.EXPIRE";
        /** The sweep re-read a funding it had left without an answer. */
        public static final String RECONCILE = "VISITS.RECONCILE";

        /** 5.2: the owner's stamp names a human, who must then accept in person. */
        public static final String ASSIGN = "VISITS.ASSIGN";
        public static final String ACCEPT = "VISITS.ACCEPT";
        public static final String REJECT = "VISITS.REJECT";

        /** The owner declines a paid request before accepting it: the money returns in full. */
        public static final String DECLINE = "VISITS.DECLINE";

        /**
         * The planning referee: each time the time is edited the entry records
         * who changed it, so the turn is derived from history, never from a flag.
         */
        public static final String TIME_PROPOSE = "VISITS.TIME_PROPOSE";
        public static final String TIME_CONFIRM = "VISITS.TIME_CONFIRM";

        /**
         * 5.3: the claim that the visit is happening, and the countersignature
         * that keeps claims honest. Both record the instant and the human,
         * because every no-show penalty reads this pair as its evidence.
         */
        public static final String START = "VISITS.START";
        public static final String START_CONFIRM = "VISITS.START_CONFIRM";

        /** The mutual judgment — one act per party, filed to the reputation host. */
        public static final String ASSESS = "VISITS.ASSESS";

        /** The visit completed and the held money moved to its born destination. */
        public static final String COMPLETE = "VISITS.COMPLETE";

        /** A claim lapsed: the party who was not there pays (the no-show laws). */
        public static final String NO_SHOW = "VISITS.NO_SHOW";

        /** 5.4: the free door (a new time) and the paid one (breaking a commitment). */
        public static final String RESCHEDULE = "VISITS.RESCHEDULE";
        public static final String CANCEL = "VISITS.CANCEL";
        /** The direct penalty payment a cancellation raises, on its own row. */
        public static final String PENALTY = "VISITS.PENALTY";

        /**
         * 5.5: a staff decision on a disputed visit — the money settled to one
         * side or returned, through notraius's own payment door.
         */
        public static final String RESOLVE = "VISITS.RESOLVE";

        /** The owner's visit price (physical/virtual), one per kind per property. */
        public static final String PRICING_CREATE = "VISIT_PRICINGS.CREATE";
        public static final String PRICING_UPDATE = "VISIT_PRICINGS.UPDATE";
        public static final String PRICING_PAUSE  = "VISIT_PRICINGS.PAUSE";
        public static final String PRICING_RESUME = "VISIT_PRICINGS.RESUME";
        public static final String PRICING_DELETE = "VISIT_PRICINGS.DELETE";
        /** The property's visit prices — read with the property. */
        public static final String PRICING_LIST = "VISIT_PRICINGS.LIST";
    }

    // ── Terms (asset 4.3 + 4.1 selections) ───────────────────────────────
    public static final class Terms {
        private Terms() {}
        /** The owner's number inside the admin's standard. */
        public static final String POLICY_VALUE_SET  = "PROPERTY_POLICIES.SET";
        public static final String POLICY_VALUE_LIST = "PROPERTY_POLICIES.LIST";
        /**
         * Removing the number again: the plan's own escape (PLAN.md 4.3) — the
         * owner forgets the count-kind values before changing a pricing's
         * duration, instead of being locked into it forever.
         */
        public static final String POLICY_VALUE_FORGET = "PROPERTY_POLICIES.FORGET";
        /** The owner's selection of a rule (own or public) with its window. */
        public static final String RULE_SELECT = "PROPERTY_RULES.SELECT";
        /** Removing a selection the property holds — its own exit. */
        public static final String RULE_FORGET = "PROPERTY_RULES.FORGET";
        public static final String RULE_LIST   = "PROPERTY_RULES.LIST";
    }

    // ── Leases (PLAN Part 6) ─────────────────────────────────────────────
    public static final class Leases {
        private Leases() {}
        /**
         * Birth: one act that mints the PENDING lease, derives and funds the
         * whole deposit matrix, and flips the property to LEASED. Its audit
         * entry carries the payment the funding call returned.
         */
        public static final String CREATE = "LEASES.CREATE";
        /** The retried birth found its own funding already standing. */
        public static final String CREATE_ADOPT = "LEASES.CREATE_ADOPT";
        /** The funding call left without an answer; the row stands PENDING. */
        public static final String FUND_UNKNOWN = "LEASES.FUND_UNKNOWN";
        /** The pre-commitment exit: the whole matrix goes back, escrow by escrow. */
        public static final String CANCEL = "LEASES.CANCEL";
        /** Silence ended a PENDING lease and refunded the matrix in full (6.1). */
        public static final String EXPIRE = "LEASES.EXPIRE";
        public static final String GET = "LEASES.GET";
        public static final String LIST_MINE = "LEASES.LIST_MINE";
        public static final String LIST_OWNED = "LEASES.LIST_OWNED";

        /** 6.2: the owner's commitment, and the conditions stage it opens. */
        public static final String CONFIRM = "LEASES.CONFIRM";
        public static final String CONDITION_UPLOAD = "LEASES.CONDITION_UPLOAD";
        public static final String CONDITION_CONFIRM = "LEASES.CONDITION_CONFIRM";
        /** Conditions bypassed (or absent by origin): CONFIRMED parks into ACTIVE. */
        public static final String ACTIVATE = "LEASES.ACTIVATE";

        /** 6.2: the calendar generated (or re-generated) from the validity. */
        public static final String SCHEDULE = "LEASES.SCHEDULE";
        /** 6.2's rolling horizon: an extension of the open-ended calendar. */
        public static final String SCHEDULE_EXTEND = "LEASES.SCHEDULE_EXTEND";

        /** 6.3: an installment answered by a payment of its own. */
        public static final String INSTALLMENT_PAY = "LEASES.INSTALLMENT_PAY";
        /** 6.8: the receiver's cash confirmation — a recorded fact, never custody. */
        public static final String CASH_CONFIRM = "LEASES.CASH_CONFIRM";
        /** 6.3: the overdue miss settled from the advance's custody. */
        public static final String CONSUME = "LEASES.CONSUME";
        /** 6.3: the tenant restores the cushion — its own funding reference. */
        public static final String TOP_UP = "LEASES.TOP_UP";
        /** 6.3: the automatic judgment filed for a missed installment. */
        public static final String ASSESS = "LEASES.ASSESS";
        /** 6.3: an overdue miss that custody could not cover (or a failed release). */
        public static final String OVERDUE = "LEASES.OVERDUE";

        /** 6.5: the engine's notices, from issue through answer to escalation. */
        public static final String NOTICE_ISSUE = "LEASES.NOTICE_ISSUE";
        public static final String NOTICE_ACCEPT = "LEASES.NOTICE_ACCEPT";
        public static final String NOTICE_REFUSE = "LEASES.NOTICE_REFUSE";
        public static final String NOTICE_CANCEL = "LEASES.NOTICE_CANCEL";
        public static final String NOTICE_ESCALATE = "LEASES.NOTICE_ESCALATE";
        /** The cushion reached its standard on its own: the notice closed itself. */
        public static final String NOTICE_SATISFY = "LEASES.NOTICE_SATISFY";

        /** 6.7: the end-of-life walk — settlement birth, confirmation, disputes. */
        public static final String END = "LEASES.END";
        public static final String SETTLE = "LEASES.SETTLE";
        public static final String SETTLEMENT_CONFIRM = "LEASES.SETTLEMENT_CONFIRM";
        public static final String REPAIR_CREATE = "LEASES.REPAIR_CREATE";
        public static final String REPAIR_CONFIRM = "LEASES.REPAIR_CONFIRM";
        public static final String DISPUTE = "LEASES.DISPUTE";
        public static final String RESOLVE = "LEASES.RESOLVE";

        /** 6.6: the imported lease's declaration, its verification and its end. */
        public static final String IMPORT_CREATE = "LEASES.IMPORT_CREATE";
        public static final String IMPORT_ACCEPT = "LEASES.IMPORT_ACCEPT";
        public static final String IMPORT_REFUSE = "LEASES.IMPORT_REFUSE";
    }
}
