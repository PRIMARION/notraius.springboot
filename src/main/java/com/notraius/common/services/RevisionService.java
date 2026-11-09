package com.notraius.common.services;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notraius.common.NotraiusErrors;
import com.notraius.common.dto.responses.RevisionSummaryResponse;
import com.notraius.database.entities.reference.Entities;
import com.notraius.database.repositories.LogsRepository;
import com.notraius.database.repositories.reference.EntitiesRepository;
import lombok.extern.slf4j.Slf4j;
import org.primarion.module.errors.BadRequestException;
import org.primarion.module.errors.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The revision reader — "this record, as version {@code N} stated it".
 * <p>
 * <b>One record, one row, many truths.</b> Every versioned row in notraius keeps
 * only its newest truth: editing a rule does not add a second rule, it moves the
 * same row forward and bumps {@code version}. That is the storage law, and it is
 * why a client holding {@code version: 4} and a stale copy cannot get version 3
 * back from the table — the table no longer has it.
 * <p>
 * What the table does have is the audit chain: every accepted write stores the
 * full before and after snapshot <i>in the same transaction</i> as the version
 * bump (the versioning law's second half). This service reads that chain and
 * hands the snapshot back as the same DTO the live read returns, so
 * {@code GET /pricing/{id}?version=2} answers in exactly the shape
 * {@code GET /pricing/{id}} answers — one contract, two truths.
 * <p>
 * <b>It never guesses.</b> The requested version must be stated by an audit
 * entry (or be the row's current version, which the caller short-circuits). A
 * version the chain does not hold — pruned, written before the chain carried
 * versions, or simply never existing — is refused by name ({@code NT-0212})
 * rather than answered with today's row. Answering today's row under an old
 * version number is precisely the failure this exists to stop.
 * <p>
 * <b>It cannot silently gap.</b> The four resources that publish this door write
 * their audit entries through {@code RequiredAuditWriter}, so a revision and the
 * row that states it commit together or not at all (see
 * {@code com.notraius.common.audit.RequiredAuditWriter}); the fail-soft posture
 * the rest of the module keeps cannot reach these four.
 * <p>
 * <b>Discovery is part of the contract.</b> {@link #revisionsOf} lists the
 * revisions the chain holds, so a client never learns the shape of a record's
 * past by asking for versions and reading refusals. It applies the same
 * authorisation question as a single revision, because a list of versions is a
 * weaker fact than any one of them.
 * <p>
 * <b>Authorization is the caller's duty.</b> This service knows history, not
 * visibility: the owning service decides who may read the past (the row's own
 * {@code canAccess} guard and the staff rung) and calls here only after that
 * decision, because a past revision can state something the present row no
 * longer does — a draft title, a private rule's text, a price that was paused.
 */
@Service
@Slf4j
public class RevisionService {

    private final LogsRepository logsRepository;
    private final EntitiesRepository entitiesRepository;
    private final ObjectMapper objectMapper;

    public RevisionService(LogsRepository logsRepository,
                           EntitiesRepository entitiesRepository,
                           ObjectMapper objectMapper) {
        this.logsRepository = logsRepository;
        this.entitiesRepository = entitiesRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * The record as the given revision stated it, in the live read's own shape.
     *
     * @param entityCode the audit entity namespace ({@code Entities.PRICINGS}, …)
     * @param referenceId the record's id — the audit chain's {@code reference_id}
     * @param version the revision asked for; must be at least 1
     * @param type the response DTO the snapshot was written from
     * @throws BadRequestException {@code NT-0211} when the version cannot name a revision
     * @throws NotFoundException   {@code NT-0212} when the chain does not hold it
     */
    @Transactional(readOnly = true)
    public <T> T revisionOf(String entityCode, UUID referenceId, int version, Class<T> type) {
        if (version < 1) {
            throw new BadRequestException(NotraiusErrors.REVISION_INVALID,
                    "version must be at least 1; " + version + " names no revision of "
                            + entityCode + " " + referenceId);
        }
        Entities entity = requireEntity(entityCode, referenceId, version);
        String snapshot = logsRepository.findRevisionSnapshot(entity.getId(), referenceId, version)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.REVISION_NOT_FOUND,
                        "Revision " + version + " of " + entityCode + " " + referenceId
                                + " is not in the audit chain"));
        try {
            // The snapshot was written FROM this DTO, so the round trip is exact.
            // Two features are switched off rather than tolerated: the audit sink
            // enriches new_data with the active delegation (delegatedBy, seat), and
            // a snapshot written before a field existed may not carry it — neither
            // is a client fault and neither may fail the read.
            return objectMapper.readerFor(type)
                    .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .without(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .readValue(snapshot);
        } catch (IOException e) {
            log.warn("Revision {} of {} {} is in the chain but unreadable: {}",
                    version, entityCode, referenceId, e.getMessage());
            throw new NotFoundException(NotraiusErrors.REVISION_NOT_FOUND,
                    "Revision " + version + " of " + entityCode + " " + referenceId
                            + " cannot be reconstructed from the audit chain");
        }
    }

    /**
     * Every revision the chain holds for one record, newest first.
     * <p>
     * <b>Why this exists.</b> Without it a client discovers the past by asking
     * and reading refusals: hold version 4, ask for 3, and only {@code NT-0212}
     * tells you whether 3 was ever written. A record's chain is a small, bounded
     * list — one row per revision it has had — so listing it is cheaper than any
     * guessing protocol and is the same authorisation question as reading one.
     * <p>
     * The <b>current</b> revision appears too, flagged: it is in the row, not
     * (necessarily) in the chain, and a client reconciling its copy against the
     * server wants the whole sequence, not the sequence minus the only one it can
     * already read.
     *
     * @param entityCode   the audit entity namespace ({@code Entities.PRICINGS}, …)
     * @param referenceId  the record's id
     * @param currentVersion the row's own {@code version}, flagged as current
     */
    @Transactional(readOnly = true)
    public List<RevisionSummaryResponse> revisionsOf(String entityCode, UUID referenceId, int currentVersion) {
        Entities entity = entitiesRepository.findByCode(entityCode)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.REVISION_NOT_FOUND,
                        "No audit namespace '" + entityCode + "', so no revisions of "
                                + referenceId + " are retained"));
        return logsRepository.findRevisions(entity.getId(), referenceId).stream()
                .map(row -> new RevisionSummaryResponse(row.version(), row.recordedAt(),
                        row.version() == currentVersion))
                .toList();
    }

    /**
     * What each of these records stated at an instant, in the live read's own
     * shape — the chain's other question, beside "this record at version N".
     * <p>
     * <b>Why an instant is not a version.</b> {@link #revisionOf} asks what a
     * stated revision said, which needs the version a client already holds. A
     * market page asks what a set of prices said <i>on a date</i>, without holding
     * any version at all — the case a single row cannot answer, because issuing a
     * new window overwrites the old one and a table only holds the newest truth.
     * <p>
     * <b>A record absent from the map did not exist then.</b> Either the chain has
     * no entry at or before the instant (the row was born later) or the newest
     * entry then was its removal. Both are the same answer — omit it — and both
     * must not be papered over with an earlier truth.
     * <p>
     * A snapshot that cannot be read back is omitted and logged rather than
     * failing the page: one unreadable entry in a record's past must not make the
     * whole answer unavailable, and serving the row's present truth in its place
     * would be the exact confusion this class exists to prevent.
     */
    @Transactional(readOnly = true)
    public <T> Map<UUID, T> statesAt(String entityCode, Collection<UUID> referenceIds,
                                     Instant at, Class<T> type) {
        if (referenceIds == null || referenceIds.isEmpty()) {
            return Map.of();
        }
        Entities entity = entitiesRepository.findByCode(entityCode)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.REVISION_NOT_FOUND,
                        "No audit namespace '" + entityCode + "', so no state at " + at + " is retained"));
        Map<UUID, T> states = new HashMap<>();
        for (LogsRepository.StateRow row : logsRepository.findStatesAt(entity.getId(), referenceIds, at)) {
            if (row.snapshot() == null) {
                continue;
            }
            try {
                states.put(row.referenceId(), objectMapper.readerFor(type)
                        .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .without(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                        .readValue(row.snapshot()));
            } catch (IOException e) {
                log.warn("State of {} {} at {} is in the chain but unreadable: {}",
                        entityCode, row.referenceId(), at, e.getMessage());
            }
        }
        return states;
    }

    private Entities requireEntity(String entityCode, UUID referenceId, int version) {
        return entitiesRepository.findByCode(entityCode)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.REVISION_NOT_FOUND,
                        "No audit namespace '" + entityCode + "', so no revision " + version
                                + " of " + referenceId + " is retained"));
    }
}
