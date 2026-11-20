package com.notraius.database.repositories.lease;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notraius.database.entities.lease.Log;
import com.notraius.database.repositories.lease.reference.duration.EntitiesRepository;
import org.primarion.module.app.access.consent.database.ActivityRepository;
import org.primarion.module.communication.infra.ops.JdbcSavepoint;
import org.primarion.module.communication.infra.anomaly.partition.LogTableResolver;
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
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.notraius.common.services.logs.LogsService;

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

    /**
     * One revision of one record, reconstructed from the audit chain.
     * <p>
     * <b>Why the chain and not the row.</b> PLAN.md Part 1's versioning law
     * gives a record ONE row that always holds its newest truth and a
     * {@code version} that states which truth that is. The row therefore cannot
     * answer "what did this look like at version 2" — the chain can, because
     * every write stores the full old and new snapshot in the same transaction
     * as the version bump. A revision is whichever side of an entry states the
     * version asked for: an entry's {@code new_data} (the write that created it)
     * or a later entry's {@code old_data} (the write that replaced it). Both
     * sides are asked, so a revision whose creating entry is missing — the one
     * gap a pruned chain can leave — is still answered by the entry that
     * replaced it.
     * <p>
     * <b>Why the predicate is a column and not JSON.</b> V41 materialises the
     * version each side states ({@code new_version}, {@code old_version}) as
     * generated columns with partial indexes, so both branches of the union are
     * an index seek on {@code (entity_id, reference_id, <version>)} instead of a
     * walk over the record's whole history evaluating a CASE per entry. The
     * guards that made the JSON cast safe live in V41's generated expression now;
     * a malformed payload is NULL there rather than a SQL error here.
     * <p>
     * {@code ORDER BY created_at DESC, id DESC} inside each branch, and again over
     * the union, makes the answer the <i>newest</i> truth recorded for that
     * revision and makes two entries in the same millisecond deterministic (the
     * same tie-break the turn referee uses).
     * <p>
     * A missing snapshot is a real answer, not an error to paper over: the four
     * history-bearing writes fail closed ({@code LogsService.createOrFail}), but
     * the rest of the module stays fail-soft by design, and a pruned or
     * pre-versioning row can still leave a hole. The caller must refuse rather
     * than serve today's truth under yesterday's version number.
     */
    public Optional<String> findRevisionSnapshot(UUID entityId, UUID referenceId, int version) {
        String sql = "SELECT snapshot::text FROM ("
                + "SELECT created_at, id, new_data AS snapshot FROM " + logsTable
                + " WHERE entity_id = ? AND reference_id = ? AND new_version = ?"
                + " UNION ALL "
                + "SELECT created_at, id, old_data FROM " + logsTable
                + " WHERE entity_id = ? AND reference_id = ? AND old_version = ?"
                + ") rev WHERE snapshot IS NOT NULL"
                + " ORDER BY created_at DESC, id DESC LIMIT 1";
        List<String> rows = jdbc.query(sql, (rs, rowNum) -> rs.getString(1),
                entityId, referenceId, version, entityId, referenceId, version);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /**
     * Which revisions of one record the chain holds, newest first — the answer
     * {@code /revisions} gives, so a client never has to discover a version by
     * asking for it and reading a refusal.
     * <p>
     * One row per revision, stamped with the instant the <i>newest</i> entry
     * stating it was written — the same entry {@link #findRevisionSnapshot} would
     * serve — because a revision can be restated by a later entry (a pause, a
     * backlog sweep) and this listing must agree with the read it indexes.
     * {@code GROUP BY} on the generated column is the whole query: the partial
     * index on {@code (entity_id, reference_id, new_version)} bounds it to one
     * record's versioned entries.
     */
    public List<RevisionRow> findRevisions(UUID entityId, UUID referenceId) {
        String sql = "SELECT new_version AS version, MAX(created_at) AS recorded_at FROM "
                + logsTable
                + " WHERE entity_id = ? AND reference_id = ? AND new_version IS NOT NULL"
                + " GROUP BY new_version ORDER BY new_version DESC";
        return jdbc.query(sql,
                (rs, rowNum) -> new RevisionRow(
                        rs.getInt("version"),
                        rs.getTimestamp("recorded_at").toInstant()),
                entityId, referenceId);
    }

    /** One revision of one record: the number, and when it became the newest truth. */
    public record RevisionRow(int version, Instant recordedAt) {
    }

    /**
     * The state each of these records stated at an instant — "what did this page
     * of prices say on the third of March".
     * <p>
     * <b>Why the chain again.</b> A row's window is rewritten in place when the
     * owner issues a new validity, so the table cannot answer what an <i>expired</i>
     * window said before it was replaced. The chain can: every write stored the
     * full snapshot, so the state at an instant is the {@code new_data} of the
     * newest entry written at or before it. One query for a whole page
     * ({@code DISTINCT ON reference_id}) instead of one per row.
     * <p>
     * <b>A null snapshot is a real answer.</b> The newest entry at that instant
     * can be a removal ({@code FORGET}, {@code DELETE}) whose {@code new_data} is
     * null — the record did not exist then, and the caller must omit it rather
     * than fall back to an earlier truth.
     * <p>
     * <b>The index it needs.</b> {@code (entity_id, reference_id, created_at DESC,
     * id DESC)} — the ordering is exactly {@code DISTINCT ON}'s, so each record's
     * seek stops at its first row. V42 reinstates that index for this reader;
     * V40's copy was dropped when its only reader was replaced, and a third btree
     * on every audit insert is a cost that has to be earned.
     */
    public List<StateRow> findStatesAt(UUID entityId, Collection<UUID> referenceIds, Instant at) {
        if (referenceIds == null || referenceIds.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("SELECT DISTINCT ON (reference_id) reference_id, new_data::text")
                .append(" FROM ").append(logsTable)
                .append(" WHERE entity_id = ? AND created_at <= ? AND reference_id IN (");
        List<Object> params = new ArrayList<>();
        params.add(entityId);
        params.add(ts(at));
        int i = 0;
        for (UUID referenceId : referenceIds) {
            sql.append(i++ == 0 ? "?" : ", ?");
            params.add(referenceId);
        }
        sql.append(") ORDER BY reference_id, created_at DESC, id DESC");
        return jdbc.query(sql.toString(),
                (rs, rowNum) -> new StateRow(
                        rs.getObject("reference_id", UUID.class),
                        rs.getString(2)),
                params.toArray());
    }

    /** One record's state at an instant: its id and the snapshot, possibly null (gone). */
    public record StateRow(UUID referenceId, String snapshot) {
    }

    /**
     * The latest entry of one reference among a set of activities — the turn
     * referee. Ordered by the log's own clock, so the transaction that committed
     * last owns the move and no edit is lost to a race.
     */
    public Optional<Log> findLatestByReferenceAndActivities(UUID referenceId, List<UUID> activityIds) {
        if (activityIds == null || activityIds.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(logsTable)
                .append(" WHERE reference_id = ? AND activity_id IN (");
        List<Object> params = new ArrayList<>();
        params.add(referenceId);
        for (int i = 0; i < activityIds.size(); i++) {
            sql.append(i == 0 ? "?" : ", ?");
            params.add(activityIds.get(i));
        }
        sql.append(") ORDER BY created_at DESC, id DESC LIMIT 1");
        List<Log> results = jdbc.query(sql.toString(), this::mapRow, params.toArray());
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    /**
     * How many times one person performed one act on one reference — the
     * "once per person per visit" gates (5.4's reschedule) are read from the
     * same history that records them, never from a flag.
     */
    public long countByReferenceActivityAndCreator(UUID referenceId, UUID activityId, UUID createdBy) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + logsTable
                        + " WHERE reference_id = ? AND activity_id = ? AND created_by = ?",
                Long.class, referenceId, activityId, createdBy);
        return count != null ? count : 0L;
    }

    /** pgjdbc 42.7.x cannot bind java.time.Instant — convert at the bind (repo convention). */
    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
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
