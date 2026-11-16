package com.notraius.common.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.notraius.database.entities.lease.Lease;
import com.notraius.database.entities.reference.Entities;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.primarion.module.communication.notification.service.NotificationService;
import org.primarion.module.infra.webhook.service.WebhookDispatchHelper;
import org.springframework.stereotype.Service;

/**
 * The lease story's one voice (PLAN Part 6).
 * <p>
 * Everything a lease does concerns two people at once — the tenant and the
 * owner — and until now both had to poll: a lease signed, a notice issued, a
 * settlement born, an expiry swept all happened in silence. Every such act now
 * passes through this one announcer, so the webhook payload and the in-app
 * sentence have a single shape and a later transition cannot be forgotten at
 * one call site while the others announce.
 * <p>
 * <b>Best-effort by contract.</b> Dispatch is fail-soft inside the shared
 * helper; a refused notice is logged and swallowed. An announcement must never
 * roll back the money or the state it describes — the lease is a fact whether
 * or not the postman arrived.
 */
@Service
@Slf4j
public class LeaseAnnouncer {

    private static final String REFERENCE_ENTITY = Entities.LEASES;

    private final WebhookDispatchHelper webhookDispatchHelper;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public LeaseAnnouncer(WebhookDispatchHelper webhookDispatchHelper,
                          NotificationService notificationService,
                          ObjectMapper objectMapper) {
        this.webhookDispatchHelper = webhookDispatchHelper;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    /**
     * Announce one lease transition to both parties.
     *
     * @param lease       the lease as it stands after the act
     * @param event       the webhook operation word (CONFIRMED, NOTICE_ISSUED …)
     * @param activityCode the audit pair the event belongs to
     * @param actorId     the party who acted, if any — never notified of their
     *                    own act; {@code null} on a sweep speaks to both
     * @param title       the notice's headline
     * @param message     the notice's one sentence
     */
    public void announce(Lease lease, String event, String activityCode, UUID actorId,
                         String title, String message) {
        if (lease == null) {
            return;
        }
        Set<UUID> parties = partiesOf(lease);
        if (parties.isEmpty()) {
            return;
        }
        dispatch(lease, event, activityCode, actorId, parties);
        notifyParties(lease, actorId, parties, title, message);
    }

    /** The two people a lease binds: tenant and owner, in that order. */
    public Set<UUID> partiesOf(Lease lease) {
        LinkedHashSet<UUID> parties = new LinkedHashSet<>();
        if (lease.getUserId() != null) {
            parties.add(lease.getUserId());
        }
        if (lease.getOwnerId() != null) {
            parties.add(lease.getOwnerId());
        }
        return parties;
    }

    private void dispatch(Lease lease, String event, String activityCode, UUID actorId,
                          Set<UUID> parties) {
        if (webhookDispatchHelper == null) {
            return;
        }
        List<UUID> recipients = parties.stream()
                .filter(party -> !party.equals(actorId))
                .toList();
        if (recipients.isEmpty()) {
            return;
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("event", event);
        payload.put("leaseId", lease.getId() != null ? lease.getId().toString() : null);
        payload.put("status", lease.getStatus() != null ? lease.getStatus().getCode() : null);
        payload.put("tenantId", lease.getUserId() != null ? lease.getUserId().toString() : null);
        payload.put("ownerId", lease.getOwnerId() != null ? lease.getOwnerId().toString() : null);
        if (lease.getPricing() != null && lease.getPricing().getProperty() != null) {
            payload.put("propertyId", lease.getPricing().getProperty().getId().toString());
        }
        payload.put("rentAmount",
                lease.getRentAmount() != null ? lease.getRentAmount().toPlainString() : null);
        if (lease.getValidity() != null && lease.getValidity().getFromDate() != null) {
            payload.put("fromDate", lease.getValidity().getFromDate().toString());
        }
        if (lease.getValidity() != null && lease.getValidity().getToDate() != null) {
            payload.put("toDate", lease.getValidity().getToDate().toString());
        }
        try {
            // The dispatcher resolves the activity by FAMILY code (the registry
            // row "LEASES", not the pair "LEASES.CONFIRM") — peers pass the
            // family constant for the same reason.
            webhookDispatchHelper.dispatchToUsers(recipients, familyOf(activityCode), event, payload);
        } catch (RuntimeException e) {
            log.warn("Lease {} webhook deferred ({}): {}", lease.getId(), event, e.getMessage());
        }
    }

    /** The registry row's key: the family word before the pair's dot. */
    private static String familyOf(String activityCode) {
        int dot = activityCode.indexOf('.');
        return dot > 0 ? activityCode.substring(0, dot) : activityCode;
    }

    private void notifyParties(Lease lease, UUID actorId, Set<UUID> parties,
                               String title, String message) {
        if (notificationService == null) {
            return;
        }
        for (UUID party : parties) {
            if (party.equals(actorId)) {
                continue;
            }
            try {
                notificationService.createNotification(party, title, message,
                        REFERENCE_ENTITY, null);
            } catch (RuntimeException e) {
                log.warn("Lease {} notice deferred for user {} ({}): {}",
                        lease.getId(), party, title.toLowerCase(Locale.ROOT), e.getMessage());
            }
        }
    }
}
