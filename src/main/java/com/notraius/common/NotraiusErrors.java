package com.notraius.common;

import lombok.Getter;
import org.primarion.module.errors.ErrorCategory;
import org.primarion.module.errors.ErrorCode;

/**
 * Notraius error catalog.
 * <p>
 * Codes are {@code NT-*} and stable platform-wide: a client keys on the code,
 * never on the message. Categories follow the estate convention — a
 * {@code VALIDATION} row is a client-shape fault, a {@code BUSINESS} row is a
 * rule the domain refuses, an {@code AUTH} row is a refusal about identity or
 * standing.
 * <p>
 * Only the reference catalogue's rules live here today; each new notraius
 * module extends the same enum (the contract/logistic pattern) so one catalog
 * covers the whole host.
 */
@Getter
public enum NotraiusErrors implements ErrorCode {

    // ── Types (PLAN.md Part 1) ────────────────────────────────────────────
    TYPE_NOT_FOUND("NT-0001", "Type not found", ErrorCategory.BUSINESS),
    TYPE_ENTITY_NOT_FOUND("NT-0002", "The entity this type belongs to does not exist", ErrorCategory.VALIDATION),
    TYPE_CODE_DUPLICATE("NT-0003", "A type with this code already exists for this entity", ErrorCategory.BUSINESS),
    TYPE_PARENT_NOT_FOUND("NT-0004", "Parent type not found", ErrorCategory.VALIDATION),
    TYPE_PARENT_ENTITY_MISMATCH("NT-0005",
            "A type's entity binding must equal its parent's — the entity is the namespace", ErrorCategory.VALIDATION),
    TYPE_PARENT_CYCLE("NT-0006", "This reparent would place the type beneath its own descendant", ErrorCategory.BUSINESS),
    TYPE_IN_USE("NT-0007", "Type is referenced and cannot be deleted; retire it instead", ErrorCategory.BUSINESS),
    TYPE_RETIRED("NT-0008", "A retired type is out of the catalogue and cannot be edited", ErrorCategory.BUSINESS),

    // ── Durations (PLAN.md Part 2) ────────────────────────────────────────
    DURATION_NOT_FOUND("NT-0010", "Duration not found", ErrorCategory.BUSINESS),
    DURATION_TYPE_NOT_FOUND("NT-0011", "The unit type this duration binds to does not exist", ErrorCategory.VALIDATION),
    DURATION_TYPE_NOT_LIVE("NT-0012", "A duration may only bind to an ACTIVE type", ErrorCategory.VALIDATION),
    DURATION_VALUE_INVALID("NT-0013", "A duration's value must be a positive integer", ErrorCategory.VALIDATION),
    DURATION_DUPLICATE("NT-0014", "A duration for this type and value already exists", ErrorCategory.BUSINESS),
    DURATION_IN_USE("NT-0015", "Duration is used by a live pricing and cannot be edited or deleted; retire it instead",
            ErrorCategory.BUSINESS),
    DURATION_RETIRED("NT-0016", "A retired duration is out of the catalogue and cannot be edited", ErrorCategory.BUSINESS),

    // ── Amenities (PLAN.md Part 3.3) ──────────────────────────────────────
    AMENITY_NOT_FOUND("NT-0020", "Amenity not found", ErrorCategory.BUSINESS),
    AMENITY_TYPE_NOT_FOUND("NT-0021", "The amenity type does not exist", ErrorCategory.VALIDATION),
    AMENITY_TYPE_NOT_LIVE("NT-0022", "An amenity may only bind to an ACTIVE type", ErrorCategory.VALIDATION),
    AMENITY_CODE_DUPLICATE("NT-0023", "An active amenity with this code already exists", ErrorCategory.BUSINESS),
    AMENITY_INVALID_STATE("NT-0024", "Operation not allowed for the amenity's current status", ErrorCategory.BUSINESS),
    AMENITY_IN_USE("NT-0025", "Amenity is held by a property and cannot be retired or deleted", ErrorCategory.BUSINESS),
    AMENITY_DATA_TYPE_INVALID("NT-0026", "Amenity data type must be BOOLEAN, INTEGER or STRING",
            ErrorCategory.VALIDATION),

    // ── Rules (PLAN.md 3.1) ───────────────────────────────────────────────
    RULE_NOT_FOUND("NT-0030", "Rule not found", ErrorCategory.BUSINESS),
    RULE_INVALID_STATE("NT-0031", "Operation not allowed for the rule's current status", ErrorCategory.BUSINESS),
    RULE_IN_USE("NT-0032", "Rule is selected by a live property and its status cannot be disabled",
            ErrorCategory.BUSINESS),
    RULE_VISIBILITY_ORPHANS("NT-0033",
            "Another user's property selects this rule; making it private would orphan that selection",
            ErrorCategory.BUSINESS),
    RULE_VISIBILITY_INVALID("NT-0034", "Rule visibility must be PUBLIC or PRIVATE", ErrorCategory.VALIDATION),

    // ── Policies (PLAN.md 3.2) ────────────────────────────────────────────
    POLICY_NOT_FOUND("NT-0040", "Policy not found", ErrorCategory.BUSINESS),
    POLICY_ENTITY_NOT_FOUND("NT-0041", "The entity this policy belongs to does not exist", ErrorCategory.VALIDATION),
    POLICY_TYPE_NOT_FOUND("NT-0042", "The policy type does not exist", ErrorCategory.VALIDATION),
    POLICY_TYPE_ENTITY_MISMATCH("NT-0043",
            "A policy's type must belong to the entity that namespaces it", ErrorCategory.VALIDATION),
    POLICY_CODE_DUPLICATE("NT-0044", "A policy with this code already exists for this entity",
            ErrorCategory.BUSINESS),
    POLICY_BOUNDS_INVALID("NT-0045", "The minimum bound must not exceed the maximum bound",
            ErrorCategory.VALIDATION),
    POLICY_UNIT_INVALID("NT-0046", "Policy unit must be UNITS or AMOUNT", ErrorCategory.VALIDATION),
    POLICY_INVALID_STATE("NT-0047", "Operation not allowed for the policy's current status",
            ErrorCategory.BUSINESS),

    // ── Properties (PLAN.md 4.1) ──────────────────────────────────────────
    PROPERTY_NOT_FOUND("NT-0050", "Property not found", ErrorCategory.BUSINESS),
    PROPERTY_KYC_REQUIRED("NT-0051",
            "Only a KYC-verified identity may create or publish a property", ErrorCategory.AUTH),
    PROPERTY_KYC_UNAVAILABLE("NT-0052",
            "Identity verification could not be read; the gate fails closed", ErrorCategory.AUTH),
    PROPERTY_TYPE_NOT_FOUND("NT-0053", "The property type does not exist", ErrorCategory.VALIDATION),
    PROPERTY_TYPE_NOT_LIVE("NT-0054", "A property may only use an ACTIVE type", ErrorCategory.VALIDATION),
    PROPERTY_TYPE_ENTITY_MISMATCH("NT-0055",
            "A property's type must belong to the PROPERTY entity", ErrorCategory.VALIDATION),
    PROPERTY_LOCATION_REQUIRED("NT-0056",
            "A property needs a location before it can be published", ErrorCategory.VALIDATION),
    PROPERTY_LOCATION_UNRESOLVED("NT-0057",
            "The location could not be resolved to a state; the property cannot be anchored",
            ErrorCategory.BUSINESS),
    PROPERTY_LOCATION_IMMUTABLE("NT-0058",
            "A property's location is set once and is immutable forever after", ErrorCategory.BUSINESS),
    PROPERTY_CURRENCY_UNRESOLVED("NT-0059",
            "The resolved state carries no currency; the property cannot be anchored",
            ErrorCategory.BUSINESS),
    PROPERTY_INVALID_STATE("NT-0060",
            "Operation not allowed for the property's current status", ErrorCategory.BUSINESS),
    PROPERTY_INCOMPLETE("NT-0061",
            "The property does not meet the publication gates", ErrorCategory.BUSINESS),
    PROPERTY_IN_USE("NT-0062",
            "A live visit or lease still references this property; it cannot be removed",
            ErrorCategory.BUSINESS),
    PROPERTY_STATE_UNRESOLVABLE("NT-0063",
            "The property has no resolved state, so no state-dependent act is possible",
            ErrorCategory.BUSINESS),
    PROPERTY_SORT_UNSUPPORTED("NT-0064",
            "The market page does not offer this sort; use freshness or popular",
            ErrorCategory.VALIDATION),

    // ── Amenity holdings (PLAN.md 3.3 / 4.1) ──────────────────────────────
    PROPERTY_AMENITY_NOT_FOUND("NT-0065", "The property does not hold this amenity",
            ErrorCategory.BUSINESS),
    PROPERTY_AMENITY_NOT_LIVE("NT-0066",
            "Only an ACTIVE catalogue amenity may be selected", ErrorCategory.VALIDATION),
    PROPERTY_AMENITY_VALUE_INVALID("NT-0067",
            "The value does not match the amenity's data type", ErrorCategory.VALIDATION),
    PROPERTY_AMENITY_INVALID_STATE("NT-0068",
            "Operation not allowed for the holding's current status", ErrorCategory.BUSINESS),

    // ── Pricings (PLAN.md 4.2) ────────────────────────────────────────────
    PRICING_NOT_FOUND("NT-0070", "Pricing not found", ErrorCategory.BUSINESS),
    PRICING_TYPE_NOT_FOUND("NT-0071", "The pricing type does not exist", ErrorCategory.VALIDATION),
    PRICING_TYPE_NOT_LIVE("NT-0072", "A pricing may only use an ACTIVE type", ErrorCategory.VALIDATION),
    PRICING_TYPE_ENTITY_MISMATCH("NT-0073",
            "A pricing's type must belong to the PRICINGS entity", ErrorCategory.VALIDATION),
    PRICING_TYPE_ALREADY_PRICED("NT-0074",
            "This property already prices this type; a new window is issued on that same row",
            ErrorCategory.BUSINESS),
    PRICING_DURATION_NOT_FOUND("NT-0075", "The pricing duration does not exist", ErrorCategory.VALIDATION),
    PRICING_DURATION_NOT_LIVE("NT-0076", "A pricing may only use an ACTIVE duration",
            ErrorCategory.VALIDATION),
    PRICING_AMOUNT_INVALID("NT-0077", "A pricing amount must be strictly positive",
            ErrorCategory.VALIDATION),
    PRICING_WINDOW_INVALID("NT-0078",
            "A pricing window must end after it begins (half-open: [from, to))",
            ErrorCategory.VALIDATION),
    PRICING_INVALID_STATE("NT-0079",
            "Operation not allowed for the pricing's current status", ErrorCategory.BUSINESS),
    PRICING_IN_USE("NT-0080",
            "A policy value or a lease depends on this pricing; it cannot be removed",
            ErrorCategory.BUSINESS),
    PRICING_EXPIRED("NT-0081",
            "The pricing's window does not contain now; issue a new validity first",
            ErrorCategory.BUSINESS),
    PRICING_TYPE_CHANGE_BLOCKED("NT-0082",
            "Count-valued policies are attached to this pricing; changing its type would reinterpret them",
            ErrorCategory.BUSINESS),
    PRICING_DURATION_CHANGE_BLOCKED("NT-0084",
            "Count-valued policies are attached to this pricing; changing its duration would reinterpret them",
            ErrorCategory.BUSINESS),
    PRICING_POLICY_MINIMUM_MISSING("NT-0083",
            "A mandatory standard has no minimum, so no value can be seeded from it",
            ErrorCategory.BUSINESS),

    // ── Terms: policy values and rule selections (PLAN.md 4.3) ────────────
    TERM_POLICY_NOT_FOUND("NT-0090", "Policy not found", ErrorCategory.BUSINESS),
    TERM_POLICY_NOT_APPLICABLE("NT-0091",
            "No standard resolves for this property's state and the policy's context",
            ErrorCategory.BUSINESS),
    TERM_POLICY_DISABLED("NT-0092",
            "The standard is paused; its values cannot be changed until it is re-enabled",
            ErrorCategory.BUSINESS),
    TERM_VALUE_OUT_OF_BOUNDS("NT-0093",
            "The value is outside the standard's bounds", ErrorCategory.VALIDATION),
    TERM_VALUE_REQUIRED("NT-0094",
            "A mandatory standard needs a value before the property publishes",
            ErrorCategory.BUSINESS),
    TERM_VALUE_NOT_SET("NT-0095", "This pricing carries no value for that policy",
            ErrorCategory.BUSINESS),
    TERM_VALUE_INVALID("NT-0096",
            "The value must be greater than zero, and a whole number when the standard counts units",
            ErrorCategory.VALIDATION),
    TERM_RULE_NOT_FOUND("NT-0097", "Rule not found", ErrorCategory.BUSINESS),
    TERM_RULE_NOT_SELECTABLE("NT-0098",
            "Only your own rules and public rules may be selected", ErrorCategory.BUSINESS),
    TERM_RULE_NOT_LIVE("NT-0099", "A disabled or retired rule cannot be selected",
            ErrorCategory.BUSINESS),
    TERM_RULE_SELECTION_NOT_FOUND("NT-0100", "The property does not hold this rule selection",
            ErrorCategory.BUSINESS),
    TERM_RULE_ALREADY_SELECTED("NT-0101",
            "This rule is already selected for an overlapping window on this property",
            ErrorCategory.BUSINESS),
    TERM_WINDOW_INVALID("NT-0102",
            "A window must end after it begins (half-open: [from, to))",
            ErrorCategory.VALIDATION),

    // ── Visits · request and automatic payment (PLAN.md 5.1) ──────────────
    VISIT_PRICING_NOT_FOUND("NT-0110", "Visit pricing not found", ErrorCategory.BUSINESS),
    VISIT_PRICING_NOT_LIVE("NT-0111",
            "This visit price is paused and cannot be booked", ErrorCategory.BUSINESS),
    VISIT_PROPERTY_NOT_PUBLISHED("NT-0112",
            "The property is not published, so it cannot be visited", ErrorCategory.BUSINESS),
    VISIT_NOT_FOUND("NT-0113", "Visit not found", ErrorCategory.BUSINESS),
    VISIT_WINDOW_INVALID("NT-0114",
            "A visit window must end after it begins and fit the visit's duration",
            ErrorCategory.VALIDATION),
    VISIT_PAYMENT_REFUSED("NT-0115",
            "The payment was refused, so the visit was not requested", ErrorCategory.BUSINESS),
    VISIT_PAYMENT_UNAVAILABLE("NT-0116",
            "The payment service did not answer; the visit awaits reconciliation",
            ErrorCategory.BUSINESS),
    VISIT_CURRENCY_UNRESOLVED("NT-0117",
            "The property's currency cannot be resolved for the payment service",
            ErrorCategory.BUSINESS),
    VISIT_INVALID_STATE("NT-0118",
            "Operation not allowed for the visit's current status", ErrorCategory.BUSINESS),
    VISIT_PAYMENT_ACTIVITY_MISSING("NT-0119",
            "The configured payment activity does not exist in the payment service",
            ErrorCategory.BUSINESS),
    VISIT_OWN_PROPERTY("NT-0120",
            "A property's owner cannot request a visit of their own property",
            ErrorCategory.BUSINESS),
    VISIT_PRICING_IN_USE("NT-0121",
            "A visit was already sold at this price; pause it instead of removing it",
            ErrorCategory.BUSINESS),
    VISIT_PRICING_ALREADY_PRICED("NT-0122",
            "This property already offers this visit kind; edit that price instead",
            ErrorCategory.BUSINESS),
    VISIT_PRICING_TYPE_NOT_FOUND("NT-0123", "The visit kind does not exist",
            ErrorCategory.VALIDATION),
    VISIT_PRICING_TYPE_ENTITY_MISMATCH("NT-0124",
            "A visit kind must belong to the VISIT_PRICINGS entity", ErrorCategory.VALIDATION),
    VISIT_PRICING_TYPE_NOT_LIVE("NT-0125",
            "A visit price may only use an ACTIVE kind", ErrorCategory.VALIDATION),
    VISIT_PRICING_AMOUNT_INVALID("NT-0126",
            "A visit price must be strictly positive", ErrorCategory.VALIDATION),
    VISIT_PRICING_DURATION_INVALID("NT-0127",
            "A visit must last a whole, positive number of minutes", ErrorCategory.VALIDATION),

    // ── Visits: acceptance and planning (PLAN.md 5.2) ────────────────────
    VISIT_ASSIGNEE_INVALID("NT-0128",
            "The assigned user must be someone other than the visitor and the property owner",
            ErrorCategory.VALIDATION),
    VISIT_ASSIGNEE_UNREACHABLE("NT-0129",
            "Identity could not be reached to verify the assigned user", ErrorCategory.AUTH),
    VISIT_ASSIGNEE_MISMATCH("NT-0130",
            "Only the user this visit is assigned to may accept or reject it", ErrorCategory.AUTH),
    VISIT_TURN_CLOSED("NT-0131",
            "Your re-edit window has closed; the counterpart must confirm or counter",
            ErrorCategory.BUSINESS),
    VISIT_SELF_CONFIRM("NT-0132",
            "Confirming your own proposal is not agreement — the counterpart must confirm it",
            ErrorCategory.BUSINESS),
    VISIT_TIME_PROPOSAL_MISSING("NT-0133",
            "There is no proposed time to confirm", ErrorCategory.BUSINESS),
    VISIT_SCHEDULE_FORBIDDEN("NT-0134",
            "Only the visitor and the assigned user may edit a visit's time", ErrorCategory.AUTH),
    VISIT_START_TOO_EARLY("NT-0135",
            "The visit's planned time has not arrived yet", ErrorCategory.BUSINESS),
    VISIT_START_TOO_LATE("NT-0136",
            "The visit's planned time has passed; a visit that never started expires",
            ErrorCategory.BUSINESS),
    VISIT_START_SELF_CONFIRM("NT-0137",
            "You cannot countersign your own start claim — the other party must confirm it",
            ErrorCategory.BUSINESS),
    VISIT_START_FORBIDDEN("NT-0138",
            "Only the visitor and the assigned user may claim or countersign a visit's start",
            ErrorCategory.AUTH),
    VISIT_NOT_STARTED("NT-0139",
            "The visit has not started yet", ErrorCategory.BUSINESS),
    VISIT_ASSESSMENT_WINDOW_CLOSED("NT-0140",
            "The assessment window for this visit has closed", ErrorCategory.BUSINESS),
    VISIT_ASSESSMENT_FORBIDDEN("NT-0141",
            "Only the visitor and the property's side may assess a visit", ErrorCategory.AUTH),
    VISIT_ALREADY_ASSESSED("NT-0142",
            "You have already assessed this visit; a filed assessment stands",
            ErrorCategory.BUSINESS),
    VISIT_ASSESSMENTS_INCOMPLETE("NT-0143",
            "Both parties must assess before the visit can be confirmed complete",
            ErrorCategory.BUSINESS),
    VISIT_COMPLETION_FORBIDDEN("NT-0144",
            "Only the visitor and the assigned user may complete a visit", ErrorCategory.AUTH),
    VISIT_CANCEL_FORBIDDEN("NT-0145",
            "Only the visitor may cancel a confirmed visit", ErrorCategory.AUTH),
    VISIT_CANCEL_WINDOW_CLOSED("NT-0146",
            "A confirmed visit can only be cancelled while it stands at least the configured horizon in the future",
            ErrorCategory.BUSINESS),
    VISIT_RESCHEDULE_WINDOW_CLOSED("NT-0147",
            "A confirmed visit can only be rescheduled while it stands at least the configured horizon in the future",
            ErrorCategory.BUSINESS),
    VISIT_RESCHEDULE_LIMIT("NT-0148",
            "You have already used your reschedule for this visit", ErrorCategory.BUSINESS),
    VISIT_RESOLUTION_INVALID("NT-0149",
            "A staff decision must be either REFUND or RELEASE", ErrorCategory.VALIDATION),

    // ── Leases (PLAN Part 6) ──────────────────────────────────────────────
    LEASE_PRICING_NOT_LIVE("NT-0150",
            "A lease may only be born from a LIVE pricing", ErrorCategory.BUSINESS),
    LEASE_PRICING_EXPIRED("NT-0151",
            "This pricing's window has ended: history reads it, the future does not start from it",
            ErrorCategory.BUSINESS),
    LEASE_PROPERTY_NOT_AVAILABLE("NT-0152",
            "Only a published property can take on a new lease", ErrorCategory.BUSINESS),
    LEASE_OWN_PROPERTY("NT-0153",
            "A property's owner cannot lease it to themselves", ErrorCategory.VALIDATION),
    LEASE_CURRENCY_UNRESOLVED("NT-0154",
            "The property has no currency, so its deposits cannot be priced", ErrorCategory.BUSINESS),
    LEASE_STANDARD_MISSING("NT-0155",
            "A mandatory deposit standard has no value on this pricing; the lease fails closed",
            ErrorCategory.BUSINESS),
    LEASE_STANDARD_FLAGGED("NT-0156",
            "A deposit value sits outside its standard's bounds and must be reviewed before a lease uses it",
            ErrorCategory.BUSINESS),
    LEASE_DEPOSIT_TYPE_MISSING("NT-0157",
            "A deposit standard has no matching deposit type in the catalogue", ErrorCategory.BUSINESS),
    LEASE_MATRIX_INVALID("NT-0158",
            "The deposit matrix derived from this pricing is not a fundable matrix", ErrorCategory.BUSINESS),
    LEASE_PAYMENT_REFUSED("NT-0159",
            "The funding was refused, so the lease was not created", ErrorCategory.BUSINESS),
    LEASE_NOT_FOUND("NT-0160", "Lease not found", ErrorCategory.BUSINESS),
    LEASE_KYC_REQUIRED("NT-0161",
            "Only a KYC-verified identity may sign a lease", ErrorCategory.AUTH),
    LEASE_KYC_UNAVAILABLE("NT-0162",
            "The identity provider is unavailable, so the lease's KYC gate cannot be passed",
            ErrorCategory.BUSINESS),
    LEASE_WINDOW_INVALID("NT-0163",
            "The tenancy window does not have a positive length", ErrorCategory.VALIDATION),
    LEASE_CANCEL_FORBIDDEN("NT-0164",
            "Only the tenant or the property's owner may cancel a lease", ErrorCategory.AUTH),
    LEASE_CANCEL_WINDOW_CLOSED("NT-0165",
            "Only a PENDING lease can be cancelled: a committed or lived lease ends through its settlement",
            ErrorCategory.BUSINESS),
    LEASE_FUNDING_UNRESOLVED("NT-0166",
            "The funding's outcome is still unknown; ask again once pay answers",
            ErrorCategory.BUSINESS),
    LEASE_CREATE_RACE("NT-0167",
            "This property already carries a live lease", ErrorCategory.BUSINESS),

    // ── Leases: the staged machine (PLAN.md 6.2) ──────────────────────────
    LEASE_STATE_INVALID("NT-0168",
            "Operation not allowed for the lease's current status", ErrorCategory.BUSINESS),
    LEASE_CONFIRM_FORBIDDEN("NT-0169",
            "Only the property's owner may confirm this lease", ErrorCategory.AUTH),
    LEASE_CONFIRM_WINDOW_CLOSED("NT-0170",
            "Only a PENDING lease can be confirmed", ErrorCategory.BUSINESS),
    LEASE_CONDITION_FORBIDDEN("NT-0171",
            "This party may not report or confirm conditions at this stage", ErrorCategory.AUTH),
    LEASE_CONDITION_INVALID("NT-0172",
            "The condition report does not fit the lease's stage", ErrorCategory.BUSINESS),
    LEASE_CONDITIONS_OUTSTANDING("NT-0173",
            "The move-in conditions are not all confirmed yet", ErrorCategory.BUSINESS),
    LEASE_SCHEDULE_INVALID("NT-0174",
            "The lease's calendar cannot be derived from its validity", ErrorCategory.BUSINESS),

    // ── Leases: the money cycle (PLAN.md 6.3/6.8) ─────────────────────────
    LEASE_BREAKDOWN_NOT_FOUND("NT-0175", "Lease breakdown not found", ErrorCategory.BUSINESS),
    LEASE_BREAKDOWN_PAYER_FORBIDDEN("NT-0176",
            "Only the tenant may pay this installment", ErrorCategory.AUTH),
    LEASE_BREAKDOWN_ALREADY_RESOLVED("NT-0177",
            "This bill was already answered; the ledger never rewrites a satisfied row",
            ErrorCategory.BUSINESS),
    LEASE_BREAKDOWN_AMOUNT_MISMATCH("NT-0178",
            "The payment's split does not name the bill's due figure exactly",
            ErrorCategory.BUSINESS),
    LEASE_TOPUP_INVALID("NT-0179",
            "A top-up must restore a whole, positive number of units", ErrorCategory.VALIDATION),
    LEASE_TOPUP_FORBIDDEN("NT-0180",
            "Only the tenant may restore the advance cushion", ErrorCategory.AUTH),
    LEASE_TOPUP_NOT_FOUND("NT-0181", "Top-up not found", ErrorCategory.BUSINESS),
    LEASE_TOPUP_UNRESOLVED("NT-0182",
            "The top-up's funding outcome is still unknown; ask again once pay answers",
            ErrorCategory.BUSINESS),
    LEASE_FUNDING_UNAVAILABLE("NT-0183",
            "The payment service did not answer; the row awaits reconciliation",
            ErrorCategory.BUSINESS),

    // ── Leases: notices (PLAN.md 6.5) ─────────────────────────────────────
    LEASE_NOTICE_NOT_FOUND("NT-0184", "Lease notice not found", ErrorCategory.BUSINESS),
    LEASE_NOTICE_FORBIDDEN("NT-0185",
            "Only the tenant may answer this notice, and only the owner may cancel a vacation",
            ErrorCategory.AUTH),
    LEASE_NOTICE_ALREADY_ANSWERED("NT-0186",
            "This notice already carries an answer", ErrorCategory.BUSINESS),
    LEASE_NOTICE_WINDOW_CLOSED("NT-0187",
            "This notice's window has closed; the machine's next read decides",
            ErrorCategory.BUSINESS),
    LEASE_NOTICE_DUPLICATE("NT-0188",
            "An open notice of this kind already stands on the lease", ErrorCategory.BUSINESS),

    // ── Leases: end of life and disputes (PLAN.md 6.7) ────────────────────
    LEASE_SETTLEMENT_NOT_FOUND("NT-0189", "Lease settlement not found", ErrorCategory.BUSINESS),
    LEASE_SETTLEMENT_NOT_OPEN("NT-0190",
            "This settlement is not open for this act", ErrorCategory.BUSINESS),
    LEASE_SETTLEMENT_FORBIDDEN("NT-0191",
            "Only a party to the lease or staff may move its settlement", ErrorCategory.AUTH),
    LEASE_SETTLEMENT_OUTSTANDING("NT-0192",
            "A settlement still owes money; its overdue clock or the dispute door decides",
            ErrorCategory.BUSINESS),
    LEASE_END_WINDOW_CLOSED("NT-0193",
            "Only an ACTIVE lease reaches its end of life", ErrorCategory.BUSINESS),
    LEASE_REPAIR_NOT_FOUND("NT-0194", "Lease repair not found", ErrorCategory.BUSINESS),
    LEASE_REPAIR_FORBIDDEN("NT-0195",
            "Only the landlord lists repairs, and only the tenant confirms them",
            ErrorCategory.AUTH),
    LEASE_REPAIR_AMOUNT_INVALID("NT-0196",
            "A repair's charge must be strictly positive and name its payer",
            ErrorCategory.VALIDATION),
    LEASE_DISPUTE_INVALID("NT-0197",
            "This lease cannot take that dispute act in its current state", ErrorCategory.BUSINESS),

    // ── Leases: imports (PLAN.md 6.6) ─────────────────────────────────────
    LEASE_IMPORT_INVALID("NT-0198",
            "An imported lease must declare its debts, its holdings, or both",
            ErrorCategory.VALIDATION),
    LEASE_IMPORT_PERIOD_INVALID("NT-0199",
            "A declared debt bills a period the tenancy's calendar never contained",
            ErrorCategory.VALIDATION),
    LEASE_IMPORT_REFUSED("NT-0200",
            "Only the tenant of a PENDING imported lease may verify it", ErrorCategory.AUTH),
    LEASE_PARTY_FORBIDDEN("NT-0201",
            "Only a party to the lease, or staff, may read or move this record", ErrorCategory.AUTH),

    // ── Bulk doors (the List<> variant of an act) ─────────────────────────
    /** An item failed for a reason the domain did not name — retry it alone. */
    BULK_ITEM_FAILED("NT-0202",
            "This item could not be completed; nothing it wrote was kept — retry it alone",
            ErrorCategory.TECHNICAL),
    BULK_LIMIT_EXCEEDED("NT-0206",
            "A bulk call carries at most 100 ids; split the work across calls", ErrorCategory.VALIDATION),
    BULK_EMPTY("NT-0207", "A bulk call needs at least one id", ErrorCategory.VALIDATION),

    // ── Read doors: filters that were asked for and cannot be honoured ────
    /** A list filter named something the domain does not know, or an inverted window. */
    VISIT_FILTER_INVALID("NT-0208",
            "That filter cannot be honoured: use a real visit status, an ordered window, "
                    + "or sort=recent|window", ErrorCategory.VALIDATION),
    /** The same law for the tenancy pages: a named status, an ordered window, a known order. */
    LEASE_FILTER_INVALID("NT-0209",
            "That filter cannot be honoured: use a real lease status, an ordered window, "
                    + "or sort=recent|window", ErrorCategory.VALIDATION),
    /** A catalogue filter named a status or entity the catalogue does not contain. */
    FILTER_INVALID("NT-0210",
            "That filter cannot be honoured: name a status and an entity the catalogue holds, "
                    + "and a search of at most 200 characters", ErrorCategory.VALIDATION),

    // ── Revisions: one row, many truths (PLAN.md Part 1's versioning law) ──
    /** A requested revision number that cannot name any revision at all. */
    REVISION_INVALID("NT-0211",
            "A revision is a positive integer: version=1 is the row's birth", ErrorCategory.VALIDATION),
    /**
     * The revision is real but no longer retained: the row holds only its newest
     * truth and the audit trail keeps the chain, so a missing entry is a gap in
     * the record — refused by name rather than answered with the current row.
     */
    REVISION_NOT_FOUND("NT-0212",
            "That revision is not in this record's history; the row keeps only its current version",
            ErrorCategory.BUSINESS),

    // ── Standards and the money they become ───────────────────────────────
    /**
     * A standard is named at its birth and funded at a lease's birth: the deposit
     * matrix resolves each commitment through a {@code LEASE_DEPOSITS} type of the
     * same code. Creating the standard first would make every property in its
     * world publishable and unrentable, so the order is refused where an admin
     * can still fix it (PLAN.md 4.3: the plan does not care which kind a standard
     * is — but the matrix must be able to name it).
     */
    POLICY_DEPOSIT_TYPE_MISSING("NT-0213",
            "Create the LEASE_DEPOSITS type for this code first: every lease names its custody by it",
            ErrorCategory.BUSINESS);

    private final String code;
    private final String message;
    private final ErrorCategory category;

    NotraiusErrors(String code, String message, ErrorCategory category) {
        this.code = code;
        this.message = message;
        this.category = category;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }

    @Override
    public ErrorCategory category() {
        return category;
    }
}
