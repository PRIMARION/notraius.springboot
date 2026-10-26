package com.notraius.common.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.primarion.module.access.activity.database.entities.Activity;
import com.notraius.database.entities.reference.Entities;
import com.notraius.database.entities.Log;
import org.primarion.module.security.auth.session.SessionContextManager;
import org.primarion.module.infra.partition.LogTableResolver;
import com.notraius.database.repositories.LogsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.sql.Timestamp;

/**
 * Audit-log orchestration: delegation enrichment, actor resolution, sorting
 * and paging. All SQL lives in {@link LogsRepository} — this service never
 * touches SQL.
 */
@Service
public class LogsService {

    private static final Logger log = LoggerFactory.getLogger(LogsService.class);

    private final LogsRepository logsRepository;
    private final LogTableResolver tableResolver;
    private final SessionContextManager sessionContextManager;
    private final ObjectMapper objectMapper;

    public LogsService(LogsRepository logsRepository, LogTableResolver tableResolver,
                       SessionContextManager sessionContextManager,
                       ObjectMapper objectMapper) {
        this.logsRepository = logsRepository;
        this.tableResolver = tableResolver;
        this.sessionContextManager = sessionContextManager;
        this.objectMapper = objectMapper;
    }

    // ─── Create ────────────────────────────────────────────────────────────

    @Transactional
    public Log create(Entities entity, Activity activity,
                      JsonNode oldData, JsonNode newData, UUID referenceId, String operation) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);
        JsonNode auditedData = withDelegation(newData);

        try {
            logsRepository.insert(id, nowTs,
                    entity != null ? entity.getId() : null,
                    referenceId,
                    oldData != null ? oldData.toString() : null,
                    auditedData != null ? auditedData.toString() : null,
                    resolveActorType(),
                    resolveActorId(),
                    activity != null ? activity.getId() : null,
                    resolveOperation(activity, operation),
                    sessionContextManager.getCurrentSessionId());
        } catch (Exception e) {
            // Audit logging must never roll back the business transaction it
            // observes (same posture as primarion/pay LogsService). The
            // repository wraps the insert in a server-side SAVEPOINT, so the
            // caught failure is real: the business transaction survives.
            log.error("Failed to insert audit log into {}: {}", tableResolver.resolveParent(), e.getMessage());
            return null;
        }

        Log logEntry = new Log();
        logEntry.setId(id);
        logEntry.setCreatedAt(now);
        logEntry.setUpdatedAt(now);
        logEntry.setEntity(entity);
        logEntry.setReferenceId(referenceId);
        logEntry.setOldData(oldData);
        logEntry.setNewData(auditedData);
        logEntry.setActor(resolveActorType());
        logEntry.setCreatedBy(resolveActorId());
        logEntry.setActivity(activity);
        logEntry.setOperation(resolveOperation(activity, operation));
        logEntry.setSessionId(sessionContextManager.getCurrentSessionId());
        return logEntry;
    }

    /**
     * Resolves the audit operation label. Callers that pass an explicit
     * operation win; otherwise the dotted activity code supplies it
     * ({@code COURSE.ASSIGN} → {@code ASSIGN}) so the column is never NULL
     * for activity-scoped writes. Undotted codes yield null.
     */
    private String resolveOperation(Activity activity, String operation) {
        if (operation != null && !operation.isBlank()) {
            return operation;
        }
        if (activity == null || activity.getCode() == null) {
            return null;
        }
        String code = activity.getCode();
        int dot = code.lastIndexOf('.');
        return dot >= 0 && dot < code.length() - 1 ? code.substring(dot + 1) : null;
    }

    /**
     * UPGRADE.md §6: enrichit new_data avec la délégation siège active —
     * "X a fait F.OP pour le compte E via le siège S". Aucun DDL : les
     * champs vivent dans le JsonNode existant. Hors délégation : inchangé.
     */
    private JsonNode withDelegation(JsonNode newData) {
        org.primarion.module.access.seat.service.DelegationContext.Grant grant =
                org.primarion.module.access.seat.service.DelegationContext.current();
        if (grant == null) {
            return newData;
        }
        com.fasterxml.jackson.databind.node.ObjectNode enriched =
                newData != null && newData.isObject()
                        ? ((com.fasterxml.jackson.databind.node.ObjectNode) newData).deepCopy()
                        : objectMapper.createObjectNode();
        if (newData != null && !newData.isObject()) {
            enriched.set("payload", newData);
        }
        enriched.put("delegatedBy", grant.grantor().toString());
        enriched.put("seat", grant.seatId().toString());
        return enriched;
    }

    @Transactional
    public Log create(Entities entity, Activity activity,
                      JsonNode oldData, JsonNode newData, UUID referenceId) {
        return create(entity, activity, oldData, newData, referenceId, null);
    }

    @Transactional
    public Log create(Entities entity, Activity activity,
                      JsonNode oldData, JsonNode newData) {
        return create(entity, activity, oldData, newData, null, null);
    }

    // ─── Read ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Optional<Log> getById(UUID logId) {
        return logsRepository.findById(logId);
    }

    @Transactional(readOnly = true)
    public Page<Log> listByEntity(Entities entity, Pageable pageable) {
        // LIMIT/OFFSET pushed into SQL — the old shape loaded every row for
        // the entity and sliced it in Java.
        List<Log> page = logsRepository.findByEntityId(entity.getId(), pageable);
        long total = logsRepository.countByEntityId(entity.getId());
        return new PageImpl<>(page, pageable, total);
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    private String resolveActorType() {
        String actorType = sessionContextManager.getCurrentActorType();
        return actorType != null ? actorType : "SERVICE";
    }

    private UUID resolveActorId() {
        UUID actorId = sessionContextManager.getCurrentActorId();
        if (actorId != null) return actorId;
        UUID userId = sessionContextManager.getCurrentUserId();
        if (userId != null) return userId;
        // S2S / internal calls carry no user: attribute the row to the
        // calling platform app (app.apps id) rather than the nil UUID, so
        // audit trails show which app acted, not 00000000-0000-0000-0000-000000000000.
        UUID platformId = sessionContextManager.getCurrentPlatformId();
        return platformId != null ? platformId : UUID.fromString("00000000-0000-0000-0000-000000000000");
    }
}
