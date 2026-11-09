package com.notraius.database.entities.reference;

import org.primarion.database.entities.BaseEntity;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.Setter;

/**
 * Notraius-local entity registry. Identifies the rows the notraius module
 * produces (attachments, chats, feedback, notifications, events, ...) for
 * its statuses, audit logs and attachments. Grows with the notraius domain.
 */
@Entity
@Table(schema = "notraius", name = "entities")
@Getter
@Setter
public class Entities extends BaseEntity {

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    /** Shared chat module vocabulary carried in the notraius DB. */
    public static final String CHAT_MEMBERS = "CHAT_MEMBERS";
    public static final String CHAT_REQUESTS = "CHAT_REQUESTS";
    public static final String CHATS = "CHATS";
    public static final String MESSAGES = "MESSAGES";

    /** Communication module vocabulary. */
    public static final String FEEDBACK = "FEEDBACK";
    public static final String NOTIFICATIONS = "NOTIFICATIONS";
    public static final String PRIORITY = "PRIORITY";
    public static final String SUPPORT_TICKET = "SUPPORT_TICKET";

    /** Access/app/event/security vocabulary. */
    public static final String APPS = "APPS";
    public static final String CONSENTS = "CONSENTS";
    public static final String EVENTS = "EVENTS";
    public static final String WEBHOOKS = "WEBHOOKS";
    public static final String TOKENS = "TOKENS";
    public static final String TOKEN_PURPOSES = "TOKEN_PURPOSES";
    public static final String SETTINGS = "SETTINGS";

    /** Storage + logs. */
    public static final String ATTACHMENTS = "ATTACHMENTS";
    public static final String LOGS = "LOGS";

    /** Subscription vocabulary (the plan catalog lives here). */
    public static final String SUBSCRIPTIONS = "SUBSCRIPTIONS";

    /** Config module · reference sub-module (Part 1, Part 2, Part 3.3). */
    public static final String TYPES = "TYPES";
    public static final String DURATIONS = "DURATIONS";
    public static final String AMENITIES = "AMENITIES";
    /**
     * Namespace of the duration unit vocabulary (SECOND, MINUTE, HOUR, DAY,
     * MONTH) — duration rows bind to a type of THIS entity, never to a
     * property kind (Part 2: the entity is the namespace).
     */
    public static final String DURATION_UNIT = "DURATION_UNIT";

    /** Config module · rules sub-module (PLAN.md 3.1). */
    public static final String RULES = "RULES";

    /** Config module · policies sub-module (PLAN.md 3.2). */
    public static final String POLICIES = "POLICIES";

    /**
     * Asset module · property sub-module (PLAN.md 4.1). It is both the property
     * row's status namespace and the namespace of the property KIND catalogue:
     * a property's type is a type row under PROPERTY, never a pricing cadence.
     */
    public static final String PROPERTY = "PROPERTY";

    /** Asset module · the property's amenity holdings (PLAN.md 3.3 / 4.1). */
    public static final String PROPERTY_AMENITIES = "PROPERTY_AMENITIES";

    /**
     * Asset module · pricing sub-module (PLAN.md 4.2). Namespaces both the
     * pricing row's status and the commercial-type catalogue (NIGHTLY, …).
     */
    public static final String PRICINGS = "PRICINGS";

    /** Asset module · terms sub-module: the owner's values against policies (4.3). */
    public static final String PROPERTY_POLICIES = "PROPERTY_POLICIES";

    /** Asset module · terms sub-module: the rule selections a property holds (4.1). */
    public static final String PROPERTY_RULES = "PROPERTY_RULES";

    /**
     * Visit module · the visit-price catalogue (PLAN.md 4.1 / 5.1). Namespaces
     * both the pricing row's status and the physical/virtual kinds.
     */
    public static final String VISIT_PRICINGS = "VISIT_PRICINGS";

    /** Visit module · the visit row itself (PLAN.md 5.1–5.5). */
    public static final String VISITS = "VISITS";

    // ── Lease module (PLAN.md Part 6) ─────────────────────────────────────
    /** The tenancy row: its status vocabulary lives here. */
    public static final String LEASES = "LEASES";
    /** The deposit standards' counters on a lease (ADVANCE, SECURITY, …). */
    public static final String LEASE_DEPOSITS = "LEASE_DEPOSITS";
    /** The born escrows — one per split (6.0 atomicity). */
    public static final String LEASE_DEPOSIT_SPLITS = "LEASE_DEPOSIT_SPLITS";
    /** The calendar's money rows (INSTALLMENT, SETTLEMENT, REPAIR). */
    public static final String LEASE_BREAKDOWNS = "LEASE_BREAKDOWNS";
    /** Move-in/move-out condition reports. */
    public static final String LEASE_CONDITIONS = "LEASE_CONDITIONS";
    /** Repairs listed against a condition. */
    public static final String LEASE_REPAIRS = "LEASE_REPAIRS";
    /** The end-of-life money walk. */
    public static final String LEASE_SETTLEMENTS = "LEASE_SETTLEMENTS";
    /** The notice engine's rows (DEPOSIT, VACATION, RECONVERSION, …). */
    public static final String LEASE_NOTICES = "LEASE_NOTICES";
    /** Each top-up funding act, with its own reference (6.3's restore law). */
    public static final String LEASE_TOPUPS = "LEASE_TOPUPS";
}
