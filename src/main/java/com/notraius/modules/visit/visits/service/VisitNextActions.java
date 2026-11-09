package com.notraius.modules.visit.visits.service;

import com.notraius.config.properties.NotraiusProperties;
import com.notraius.database.entities.visit.Visit;
import com.notraius.modules.core.common.constants.NotraiusStatuses;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What this caller may do with this visit, right now — the state machine's own
 * answer, derived from the row and the clock alone.
 * <p>
 * A client that renders a "next step" by re-deriving the machine from the status
 * gets it wrong in exactly the places the server is strict: the owner's inbox
 * during REQUESTED, the assignee's ACCEPT/REJECT pair, the confirm that belongs
 * to the counterpart, the reschedule that its own horizon closed, the visit that
 * may not be claimed before its window. The row already knows all of it, so it
 * says it once, here, instead of every client guessing.
 * <p>
 * Three laws keep this honest:
 * <ul>
 *   <li><b>It is a hint, never a gate.</b> Every token names an existing endpoint,
 *       but the endpoint re-checks everything: a stale hint costs a refused call,
 *       never an illegal act.</li>
 *   <li><b>Only what the row can prove.</b> Two facts live outside the row — who
 *       proposed the current time (the turn) and whether each party already
 *       assessed. They are not invented here: PLANNING offers both time acts, and
 *       COMPLETE is left to the assessments door to decide.</li>
 *   <li><b>The counterpart's view is its own.</b> The same visit answers the
 *       visitor, the assignee and the owner differently, because the acts are
 *       theirs, not the visit's.</li>
 * </ul>
 */
public final class VisitNextActions {

    /** The owner stamps a counterpart. */
    public static final String ASSIGN = "ASSIGN";
    /** The owner turns a paid request down — full refund, no fee. */
    public static final String DECLINE = "DECLINE";
    /** The stamped counterpart takes the duty. */
    public static final String ACCEPT = "ACCEPT";
    /** The stamped counterpart hands the duty back. */
    public static final String REJECT = "REJECT";
    /** The schedule is (re)opened: propose or counter a time. */
    public static final String PROPOSE_TIME = "PROPOSE_TIME";
    /** Agree to the counterpart's proposal and enter CONFIRMED. */
    public static final String CONFIRM_TIME = "CONFIRM_TIME";
    /** Claim the visit is happening, inside its window. */
    public static final String START = "START";
    /** Countersign the other party's START claim. */
    public static final String CONFIRM_START = "CONFIRM_START";
    /** Judge the counterpart once the visit has started. */
    public static final String ASSESS = "ASSESS";
    /** Move the agreed time once, outside the cancellation horizon. */
    public static final String RESCHEDULE = "RESCHEDULE";
    /** The visitor walks away (free before CONFIRMED, penalised after). */
    public static final String CANCEL = "CANCEL";

    private VisitNextActions() {
    }

    /**
     * The acts this caller may take on this visit now, in the order the story
     * walks them; empty for a terminal visit or a caller who is not a party.
     */
    public static List<String> of(Visit visit, UUID callerId, NotraiusProperties properties) {
        if (visit == null || callerId == null) {
            return List.of();
        }
        String status = visit.getStatus() != null ? visit.getStatus().getCode() : null;
        if (status == null) {
            return List.of();
        }

        boolean visitor = callerId.equals(visit.getVisitorId());
        boolean assignee = Objects.equals(callerId, visit.getUserId());
        boolean owner = callerId.equals(visit.getOwnerId());
        if (!visitor && !assignee && !owner) {
            return List.of();
        }

        Instant now = Instant.now();
        Instant from = visit.getValidity() != null ? visit.getValidity().getFromDate() : null;
        Instant to = visit.getValidity() != null ? visit.getValidity().getToDate() : null;
        String normalized = status.toUpperCase(java.util.Locale.ROOT);
        List<String> actions = new ArrayList<>(4);

        switch (normalized) {
            case NotraiusStatuses.VISIT_REQUESTED -> {
                if (visitor) {
                    actions.add(CANCEL);
                }
                if (owner) {
                    actions.add(ASSIGN);
                    actions.add(DECLINE);
                }
            }
            case NotraiusStatuses.VISIT_ASSIGNED -> {
                if (visitor) {
                    actions.add(CANCEL);
                }
                if (assignee) {
                    actions.add(ACCEPT);
                    actions.add(REJECT);
                }
                if (owner) {
                    actions.add(ASSIGN);
                    actions.add(DECLINE);
                }
            }
            case NotraiusStatuses.VISIT_PLANNING -> {
                if (visitor || assignee) {
                    actions.add(PROPOSE_TIME);
                    actions.add(CONFIRM_TIME);
                }
                if (visitor) {
                    actions.add(CANCEL);
                }
            }
            case NotraiusStatuses.VISIT_CONFIRMED -> {
                // The two who meet claim the start; the owner only when nobody
                // was ever stamped (startRequireParty's own rule).
                boolean canMeet = visitor || assignee || (owner && visit.getUserId() == null);
                boolean insideWindow = from != null && !now.isBefore(from)
                        && (to == null || !now.isAfter(to));
                if (canMeet && insideWindow) {
                    actions.add(START);
                }
                boolean outsideHorizon = from == null || now.isBefore(
                        from.minus(properties.getVisitCancelHorizonHours(), ChronoUnit.HOURS));
                if ((visitor || assignee) && outsideHorizon) {
                    actions.add(RESCHEDULE);
                }
                if (visitor && outsideHorizon) {
                    actions.add(CANCEL);
                }
            }
            case NotraiusStatuses.VISIT_STARTED -> {
                boolean countersigned = visit.getStartConfirmedAt() != null;
                boolean claimLive = visit.getStartedAt() != null
                        && !now.isAfter(visit.getStartedAt().plus(
                                properties.getVisitStartConfirmWindowMinutes(), ChronoUnit.MINUTES));
                if (claimLive && !countersigned && !callerId.equals(visit.getStartedBy())) {
                    actions.add(CONFIRM_START);
                }
                // Assessment opens with the start and closes the configured
                // window after the planned end; the row's clock says both.
                Instant closes = to != null
                        ? to.plus(properties.getVisitAssessmentWindowMinutes(), ChronoUnit.MINUTES)
                        : null;
                boolean assessing = visit.getStartedAt() != null && closes != null
                        && !now.isAfter(closes) && (from == null || !now.isBefore(from));
                if (assessing) {
                    actions.add(ASSESS);
                }
            }
            default -> {
                // UNPAID waits on pay's word; COMPLETED/EXPIRED/CANCELLED are the end.
            }
        }
        return List.copyOf(actions);
    }
}
