package com.notraius.common.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.notraius.common.services.LogsService;
import com.notraius.database.entities.reference.Entities;
import com.notraius.database.repositories.reference.EntitiesRepository;
import org.primarion.module.access.activity.database.entities.Activity;
import org.primarion.module.access.activity.database.repositories.ActivityRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Notraius audit sink — resolves the host {@code Entities} row and the shared
 * activity row by code, then delegates to the host {@link LogsService}.
 * <p>
 * This is the single audit door of the host: shared services see only
 * {@link org.primarion.module.access.audit.AuditLogWriter}, and notraius' own
 * modules use it so every write lands in the same {@code notraius.logs}
 * partition shape with before/after data (PLAN.md Part 1's versioning law).
 * <p>
 * Fail-soft is the caller's duty (the shared interface says so): resolution of
 * an entity or activity code throws here, and a caller that lets that escape
 * would roll back a business transaction over an audit row. Two doors exist for
 * that reason — {@link #write} (fail-soft, the estate default) and
 * {@link #writeRequired} (the chain is the caller's read model, so a failure
 * must abort the write). See {@link RequiredAuditWriter}.
 */
@Component
public class NotraiusAuditLogWriter implements RequiredAuditWriter {

    private final LogsService logsService;
    private final EntitiesRepository entitiesRepository;
    private final ActivityRepository activityRepository;

    public NotraiusAuditLogWriter(LogsService logsService,
                                  EntitiesRepository entitiesRepository,
                                  ActivityRepository activityRepository) {
        this.logsService = logsService;
        this.entitiesRepository = entitiesRepository;
        this.activityRepository = activityRepository;
    }

    @Override
    public void write(String entityCode, String activityCode, UUID referenceId,
                      String operation, JsonNode oldData, JsonNode newData) {
        logsService.create(resolveEntity(entityCode), resolveActivity(activityCode),
                oldData, newData, referenceId, operation);
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

    private Entities resolveEntity(String entityCode) {
        return entitiesRepository.findByCode(entityCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown entity: " + entityCode));
    }

    private Activity resolveActivity(String activityCode) {
        return activityRepository.findByCode(activityCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown activity: " + activityCode));
    }
}
