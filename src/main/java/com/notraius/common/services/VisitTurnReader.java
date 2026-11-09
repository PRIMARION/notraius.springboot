package com.notraius.common.services;

import com.notraius.database.entities.Log;
import com.notraius.database.repositories.LogsRepository;
import com.notraius.modules.core.common.constants.NotraiusActivities;
import org.primarion.module.access.activity.database.entities.Activity;
import org.primarion.module.access.activity.database.repositories.ActivityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The planning referee (PLAN.md 5.2) — the turn is <em>derived</em> from the
 * audit history, never stored as a flag that could disagree with it.
 * <p>
 * Each time the visit's time is edited, the entry names the editor; the last
 * such entry therefore says whose edit is current, and the move belongs to the
 * counterpart. If both sides "edit simultaneously", the log's own clock —
 * transaction order — decides who was last; the loser of that race simply holds
 * the move, no edit is lost, and the rule resumes from whoever committed second.
 * <p>
 * A separate reader (rather than a method on the visit) keeps the law in one
 * place: the planning service asks "who moved last, and when?" and nothing else.
 */
@Service
public class VisitTurnReader {

    /**
     * Every act that hands the schedule to the other side. A reschedule is a
     * proposal that happens to begin from CONFIRMED rather than PLANNING, so it
     * belongs in the same history: the counterpart confirms it with the same
     * door, and the turn rule needs no second referee.
     */
    private static final List<String> TIME_EDIT_ACTIVITIES =
            List.of(NotraiusActivities.Visits.TIME_PROPOSE,
                    NotraiusActivities.Visits.RESCHEDULE);

    private final LogsRepository logsRepository;
    private final ActivityRepository activityRepository;

    public VisitTurnReader(LogsRepository logsRepository, ActivityRepository activityRepository) {
        this.logsRepository = logsRepository;
        this.activityRepository = activityRepository;
    }

    /**
     * The last time-edit of a visit, if any. Empty means nobody has edited yet —
     * the stage's opening move belongs to either party, without a clock.
     */
    @Transactional(readOnly = true)
    public Optional<Turn> lastEdit(UUID visitId) {
        List<UUID> activityIds = TIME_EDIT_ACTIVITIES.stream()
                .map(activityRepository::findByCode)
                .flatMap(Optional::stream)
                .map(Activity::getId)
                .toList();
        if (activityIds.isEmpty()) {
            return Optional.empty();
        }
        return logsRepository.findLatestByReferenceAndActivities(visitId, activityIds)
                .flatMap(log -> editorOf(log).map(editor -> new Turn(editor, log.getCreatedAt())));
    }

    private Optional<UUID> editorOf(Log log) {
        return Optional.ofNullable(log.getCreatedBy());
    }

    /** Who moved last, and when — the two facts the turn rule needs. */
    public record Turn(UUID editorId, Instant at) {
    }
}
