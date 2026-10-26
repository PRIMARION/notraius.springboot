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
}
