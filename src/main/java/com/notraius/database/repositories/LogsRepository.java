package com.notraius.database.repositories;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notraius.database.entities.Log;
import com.notraius.database.repositories.reference.EntitiesRepository;
import org.primarion.module.access.activity.database.repositories.ActivityRepository;
import org.primarion.module.infra.jdbc.JdbcSavepoint;
import org.primarion.module.infra.partition.LogTableResolver;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC access to the audit log ({@code notraius.logs}).
 * <p>
 * {@code notraius.logs} is a PostgreSQL RANGE-partitioned table (monthly
 * partitions on {@code created_at}); every statement here targets the
 * parent and PostgreSQL prunes to the matching partitions — no query
 * enumerates tables. <p>
 * All raw SQL for audit logs lives HERE — {@code LogsService} orchestrates
 * (delegation enrichment, actor resolution, sorting/paging); it never
 * touches SQL.
 */
@Repository
public class LogsRepository {

    private final JdbcTemplate jdbc;
    private final String logsTable;
    private final ActivityRepository activityRepository;
    private final EntitiesRepository entitiesRepository;
    private final ObjectMapper objectMapper;

    public LogsRepository(JdbcTemplate jdbc, LogTableResolver tableResolver,
                          ActivityRepository activityRepository,
                          EntitiesRepository entitiesRepository,
                          ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.logsTable = tableResolver.resolveParent();
        this.activityRepository = activityRepository;
        this.entitiesRepository = entitiesRepository;
        this.objectMapper = objectMapper;
    }

    // ─── Write ─────────────────────────────────────────────────────────────

    /**
     * Inserts one audit row into the partitioned parent; PostgreSQL routes
     * it to the partition covering {@code created_at} (a DEFAULT partition
     * backstops unexpected months). ?::jsonb casts: without them the JDBC
     * driver sends String parameters and PostgreSQL rejects the insert with
     * "column old_data is of type jsonb but expression is of type character
     * varying". The write runs inside a server-side SAVEPOINT
     * ({@link JdbcSavepoint}) whenever it shares the caller's transaction,
     * so a failed insert cannot poison that transaction — PostgreSQL aborts
     * the whole transaction on statement failure, and rolling back to the
     * savepoint un-aborts it. Failures still propagate; the caller applies
     * the audit-never-rolls-back policy.
     */
    public void insert(UUID id, Timestamp nowTs,
                       UUID entityId, UUID referenceId,
                       String oldData, String newData,
                       String actor, UUID createdBy,
                       UUID activityId, String operation, UUID sessionId) {
        String sql = "INSERT INTO " + logsTable +
                " (id, created_at, updated_at, entity_id, reference_id, " +
                "old_data, new_data, actor, created_by, activity_id, operation, session_id) " +
                "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?)";

        Connection con = JdbcSavepoint.txConnection(jdbc.getDataSource());
        Savepoint sp = JdbcSavepoint.enter(con, "audit_insert");
        try {
            jdbc.update(sql,
                    id, nowTs, nowTs,
                    entityId,
                    referenceId,
                    oldData,
                    newData,
                    actor,
                    createdBy,
                    activityId,
                    operation,
                    sessionId);
            JdbcSavepoint.release(con, sp);
        } catch (RuntimeException e) {
            JdbcSavepoint.rollbackTo(con, sp);
            throw e;
        }
    }

    // ─── Read ──────────────────────────────────────────────────────────────

    public Optional<Log> findById(UUID logId) {
        List<Log> results = jdbc.query(
                "SELECT * FROM " + logsTable + " WHERE id = ?",
                this::mapRow, logId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    /** One page of an entity's rows — LIMIT/OFFSET pushed into SQL. */
    public List<Log> findByEntityId(UUID entityId, Pageable pageable) {
        return jdbc.query(
                "SELECT * FROM " + logsTable
                        + " WHERE entity_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
                this::mapRow, entityId, pageable.getPageSize(), pageable.getOffset());
    }

    public long countByEntityId(UUID entityId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + logsTable + " WHERE entity_id = ?",
                Long.class, entityId);
        return count != null ? count : 0L;
    }

    /** pgjdbc 42.7.x cannot bind java.time.Instant — convert at the bind (repo convention). */
    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }

    /**
     * Usage meter for the shared subscription module: executions of one
     * activity by one user inside a validity window (to == null = open-ended).
     * A single COUNT over the parent — pruning limits the scan to the
     * partitions covering the window.
     */
    public Long countSubscriptionUsage(UUID userId, UUID activityId, String operation,
                                       Instant from, Instant to) {
        Instant upper = to != null ? to : Instant.now();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ")
                .append(logsTable)
                .append(" WHERE created_by = ? AND activity_id = ? AND UPPER(operation) = ?");
        List<Object> params = new ArrayList<>(List.of(userId, activityId, operation));
        if (from != null) {
            sql.append(" AND created_at >= ?");
            params.add(ts(from));
        }
        sql.append(" AND created_at < ?");
        params.add(ts(upper));
        Long n = jdbc.queryForObject(sql.toString(), Long.class, params.toArray());
        return n != null ? n : 0L;
    }

    // ─── Row Mapper ────────────────────────────────────────────────────────

    private Log mapRow(ResultSet rs, int rowNum) throws SQLException {
        Log logEntry = new Log();
        logEntry.setId(rs.getObject("id", UUID.class));
        logEntry.setCreatedAt(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null);
        logEntry.setUpdatedAt(rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant() : null);
        logEntry.setReferenceId(rs.getObject("reference_id", UUID.class));
        logEntry.setDeviceId(rs.getObject("device_id", UUID.class));
        logEntry.setActor(rs.getString("actor"));
        logEntry.setCreatedBy(rs.getObject("created_by", UUID.class));
        logEntry.setOperation(rs.getString("operation"));
        logEntry.setSessionId(rs.getObject("session_id", UUID.class));

        String oldDataStr = rs.getString("old_data");
        if (oldDataStr != null) {
            try { logEntry.setOldData(objectMapper.readTree(oldDataStr)); } catch (Exception ignored) {}
        }
        String newDataStr = rs.getString("new_data");
        if (newDataStr != null) {
            try { logEntry.setNewData(objectMapper.readTree(newDataStr)); } catch (Exception ignored) {}
        }

        UUID entityId = rs.getObject("entity_id", UUID.class);
        if (entityId != null) {
            entitiesRepository.findById(entityId).ifPresent(logEntry::setEntity);
        }
        UUID activityId = rs.getObject("activity_id", UUID.class);
        if (activityId != null) {
            activityRepository.findById(activityId).ifPresent(logEntry::setActivity);
        }

        return logEntry;
    }
}
