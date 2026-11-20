package com.notraius.common.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.notraius.common.services.logs.LogsService;
import com.notraius.database.entities.lease.reference.amenity.Entities;
import com.notraius.database.repositories.lease.reference.duration.EntitiesRepository;
import org.primarion.module.app.access.consent.database.Activity;
import org.primarion.module.app.access.consent.database.ActivityRepository;
import org.primarion.module.utils.flow.core.action.Action;
import org.primarion.module.utils.flow.core.action.ActionLogPort;
import org.primarion.module.utils.flow.core.action.ActionRecorder;
import org.primarion.module.app.security.ratelimit.auth.session.session.SessionContextManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.UUID;
import com.notraius.common.audit.RequiredAuditWriter;

/**
 * Notraius' single audit door and its {@link ActionLogPort}.
 *
 * <p>Two doors exist for a reason: {@link #write} is fail-soft (the estate
 * default) and {@link #writeRequired} is demanded (the chain is the caller's
 * read model, so a failed row must abort the write). Both now go through
 * {@link ActionRecorder}, which writes the audit row here and emits the fact
 * after commit.
 * <p>
 * Fail-soft is the caller's duty (the shared interface says so): resolution of
 * an entity or activity code throws here, and a caller that lets that escape
 * would roll back a business transaction over an audit row.
 */
@Component
public class NotraiusAuditLogWriter implements RequiredAuditWriter, ActionLogPort {

    private final LogsService logsService;
    private final EntitiesRepository entitiesRepository;
    private final ActivityRepository activityRepository;
    private final ActionRecorder actionRecorder;
    private final ObjectProvider<SessionContextManager> sessionContextManager;

    public NotraiusAuditLogWriter(LogsService logsService,
                                  EntitiesRepository entitiesRepository,
                                  ActivityRepository activityRepository,
                                  ActionRecorder actionRecorder,
                                  ObjectProvider<SessionContextManager> sessionContextManager) {
        this.logsService = logsService;
        this.entitiesRepository = entitiesRepository;
        this.activityRepository = activityRepository;
        this.actionRecorder = actionRecorder;
        this.sessionContextManager = sessionContextManager;
    }

    @Override
    public void write(String entityCode, String activityCode, UUID referenceId,
                      String operation, JsonNode oldData, JsonNode newData) {
        actionRecorder.record(Action.builder()
                .entity(resolveEntity(entityCode))
                .reference(referenceId)
                .activity(activityCode)
                .operation(operation == null || operation.isBlank() ? activityCode : operation)
                .audit(oldData, newData)
                .payload(JsonNodeFactory.instance.objectNode()
                        .put("id", referenceId != null ? referenceId.toString() : null))
                .build());
    }

    /**
     * The demanded door: the same entry, but the caller is told when it did not
     * land. Used by the four resources whose history is read back by version.
     */
    @Override
    public void writeRequired(String entityCode, String activityCode, UUID referenceId,
                              String operation, JsonNode oldData, JsonNode newData) {
        logsService.createOrFail(resolveEntity(entityCode), resolveActivity(activityCode),
                oldData, newData, referenceId, operation);
    }

    /** Claims notraius' own rows; the communication and market ports claim registry rows. */
    @Override
    public boolean supports(Action action) {
        return action != null && action.entity() instanceof Entities;
    }

    @Override
    public Recorded write(Action action) {
        if (!(action.entity() instanceof Entities entity)) {
            throw new IllegalStateException("notraius actions carry a notraius Entities row, got "
                    + (action.entity() == null ? "null" : action.entity().getClass().getName()));
        }
        Activity activity = resolveActivity(action.activity());
        Object log = logsService.create(entity, activity, action.oldData(), action.newData(),
                action.reference(), action.operation());
        return new Recorded(entity.getId(), actorType(), actorId(), log);
    }

    private Entities resolveEntity(String entityCode) {
        return entitiesRepository.findByCode(entityCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown entity: " + entityCode));
    }

    private Activity resolveActivity(String activityCode) {
        return activityRepository.findByCode(activityCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown activity: " + activityCode));
    }

    private String actorType() {
        SessionContextManager session = sessionContextManager.getIfAvailable();
        if (session == null) {
            return "SERVICE";
        }
        String actorType = session.getCurrentActorType();
        return actorType != null ? actorType : "SERVICE";
    }

    private UUID actorId() {
        SessionContextManager session = sessionContextManager.getIfAvailable();
        if (session == null) {
            return null;
        }
        UUID actorId = session.getCurrentActorId();
        if (actorId != null) {
            return actorId;
        }
        UUID userId = session.getCurrentUserId();
        if (userId != null) {
            return userId;
        }
        return session.getCurrentPlatformId();
    }
}
