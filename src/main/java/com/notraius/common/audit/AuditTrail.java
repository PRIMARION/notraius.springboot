package com.notraius.common.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.primarion.module.access.audit.AuditLogWriter;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The one place a notraius module writes an audit entry.
 * <p>
 * Every service used to carry its own six-line copy of this — serialise the
 * before/after snapshots, call the writer, swallow the failure with a log line
 * that named a different noun each time. Nine copies, one behaviour, and one
 * defect the copies hid: a snapshot that cannot be serialised looks exactly like
 * a successful write when the caller swallows the exception (see UPGRADE §12).
 * <p>
 * Two doors, and the difference is a decision the caller must make rather than a
 * detail it may forget:
 *
 * <ul>
 *   <li>{@link #record} — the estate default. An audit row must never roll back
 *       the business transaction it observes, so a failure is logged and the
 *       write carries on. Right for a trail humans read.</li>
 *   <li>{@link #recordRequired} — for the resources that publish their history
 *       ({@code ?version=N}, {@code /revisions}). There the chain is not a shadow
 *       of the row, it <em>is</em> the row's read model, so a failed entry must
 *       abort the write: the row's new version and its revision commit together
 *       or not at all. See {@link RequiredAuditWriter}.</li>
 * </ul>
 *
 * <b>Ordering is named, not positional.</b> The two snapshots are
 * {@code previous} and {@code current} — the writer's own {@code oldData} /
 * {@code newData} order. The old copies used {@code (after, before)}, which is
 * how one service ended up passing them the other way round; a signature that
 * cannot be mistaken is worth two extra characters at the call site.
 * <p>
 * A sink that cannot honour {@code recordRequired} is refused loudly rather than
 * silently degraded: a soft-only writer given a history-bearing resource would
 * recreate exactly the gap this class exists to close.
 */
@Component
@Slf4j
public class AuditTrail {

    private final ObjectMapper objectMapper;
    private final AuditLogWriter writer;
    private final RequiredAuditWriter strict;

    public AuditTrail(ObjectMapper objectMapper, AuditLogWriter writer) {
        this.objectMapper = objectMapper;
        this.writer = writer;
        this.strict = writer instanceof RequiredAuditWriter required ? required : null;
    }

    /**
     * Append one entry, fail-soft: a broken audit write costs a log line, never
     * the transaction that produced it.
     *
     * @param previous the record before the act, or null for a birth
     * @param current  the record after the act, or null for a removal
     */
    public void record(String entityCode, String activityCode, UUID referenceId,
                       String operation, Object previous, Object current) {
        try {
            writer.write(entityCode, activityCode, referenceId, operation,
                    payload(previous), payload(current));
        } catch (RuntimeException e) {
            // Serialisation is inside the try on purpose: a snapshot the mapper
            // cannot encode is an audit write that did not happen, and the
            // fail-soft posture says that may not fail the caller.
            log.warn("Audit entry not written for {} {} ({} {}): {}",
                    entityCode, referenceId, operation, activityCode, e.toString());
        }
    }

    /**
     * Append one entry and <b>propagate</b> any failure.
     *
     * @throws IllegalStateException when the configured sink cannot write
     *                               required entries at all
     * @throws RuntimeException      when the entry does not land
     */
    public void recordRequired(String entityCode, String activityCode, UUID referenceId,
                               String operation, Object previous, Object current) {
        if (strict == null) {
            throw new IllegalStateException("Audit sink " + writer.getClass().getSimpleName()
                    + " cannot write required entries, but " + entityCode + " " + referenceId
                    + " publishes its history and may not answer " + operation + " without one");
        }
        strict.writeRequired(entityCode, activityCode, referenceId, operation,
                payload(previous), payload(current));
    }

    private JsonNode payload(Object snapshot) {
        return snapshot == null ? null : objectMapper.valueToTree(snapshot);
    }
}
