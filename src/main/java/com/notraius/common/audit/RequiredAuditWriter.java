package com.notraius.common.audit;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * A host audit sink whose caller may <b>demand</b> the write.
 * <p>
 * The shared {@link org.primarion.module.access.audit.AuditLogWriter} states the
 * estate's default posture: a caller treats the write as fail-soft, because an
 * audit row must never roll back the business transaction it observes. That is
 * right for an audit trail read by humans.
 * <p>
 * It is not right for the four records that publish a history door
 * ({@code GET /{id}?version=N} and {@code GET /{id}/revisions}): for those, the
 * chain is not a shadow of the row, it is the row's read model. If an audit
 * write fails softly there, the row moves to version {@code N+1} while the chain
 * never learns of it, and the past answers {@code 404 NT-0212} for a version
 * that really existed. The module therefore writes those four through
 * {@link #writeRequired} and lets a failure abort the whole transaction — the
 * write and its revision commit together or not at all.
 * <p>
 * Every other write in notraius keeps the estate's fail-soft posture: nothing
 * reads its history by version, so an audit gap costs a log line, not an
 * answer.
 */
public interface RequiredAuditWriter extends org.primarion.module.access.audit.AuditLogWriter {

    /**
     * Append one audit entry, propagating any failure to the caller.
     * <p>
     * Same arguments and the same landing place as
     * {@link #write(String, String, UUID, String, JsonNode, JsonNode)}; the only
     * difference is that the caller is told when it did not land.
     *
     * @throws RuntimeException when the entry cannot be written — including an
     *                          unknown entity or activity code, both of which
     *                          mean the revision could never be reconstructed
     */
    void writeRequired(String entityCode, String activityCode, UUID referenceId,
                       String operation, JsonNode oldData, JsonNode newData);
}
