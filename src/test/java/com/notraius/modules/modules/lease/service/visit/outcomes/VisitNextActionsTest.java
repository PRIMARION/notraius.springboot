package com.notraius.modules.modules.lease.service.visit.outcomes;

import com.notraius.config.notraius.properties.NotraiusProperties;
import com.notraius.database.entities.lease.reference.status.Status;
import com.notraius.database.entities.lease.reference.amenity.Validity;
import com.notraius.database.entities.lease.visit.Visit;
import com.notraius.modules.core.core.common.constants.NotraiusStatuses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import com.notraius.modules.lease.visit.visits.service.VisitNextActions;

/**
 * The next-acts hint, as each party reads it.
 * <p>
 * These are the questions a client used to answer by re-deriving the machine —
 * and got wrong exactly where the server is strict: who may stamp, who may
 * accept, whose confirmation the time needs, when the start can be claimed, and
 * which door a closed horizon has already shut.
 */
class VisitNextActionsTest {

    private static final UUID VISITOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ASSIGNEE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    /** A signed-in user with no stake in the visit. */
    private static final UUID STRANGER = UUID.fromString("99999999-9999-9999-9999-999999999999");

    private final NotraiusProperties properties = new NotraiusProperties();

    @Test
    @DisplayName("a paid request is the owner's to stamp or decline, and only the visitor's to cancel")
    void requestedVisitSplitsByParty() {
        Visit visit = visit(NotraiusStatuses.VISIT_REQUESTED, Instant.now().plus(2, ChronoUnit.DAYS), null);

        assertThat(actions(visit, OWNER)).containsExactly(
                VisitNextActions.ASSIGN, VisitNextActions.DECLINE);
        assertThat(actions(visit, VISITOR)).containsExactly(VisitNextActions.CANCEL);
        // The counterpart's duty begins with the stamp: nobody else has an act.
        assertThat(actions(visit, STRANGER)).isEmpty();
    }

    @Test
    @DisplayName("the stamped counterpart accepts or hands the duty back")
    void assignedVisitBelongsToTheAssignee() {
        Visit visit = visit(NotraiusStatuses.VISIT_ASSIGNED, Instant.now().plus(2, ChronoUnit.DAYS), null);
        visit.setUserId(ASSIGNEE);

        assertThat(actions(visit, ASSIGNEE)).containsExactly(
                VisitNextActions.ACCEPT, VisitNextActions.REJECT);
        // The owner keeps the stamp (a re-stamp) and the decline.
        assertThat(actions(visit, OWNER)).containsExactly(
                VisitNextActions.ASSIGN, VisitNextActions.DECLINE);
        assertThat(actions(visit, VISITOR)).containsExactly(VisitNextActions.CANCEL);
    }

    @Test
    @DisplayName("planning offers both time acts — the turn decides which one the server will take")
    void planningOffersTheTimeActs() {
        Visit visit = visit(NotraiusStatuses.VISIT_PLANNING, Instant.now().plus(2, ChronoUnit.DAYS), null);
        visit.setUserId(ASSIGNEE);

        assertThat(actions(visit, VISITOR)).containsExactly(
                VisitNextActions.PROPOSE_TIME, VisitNextActions.CONFIRM_TIME, VisitNextActions.CANCEL);
        assertThat(actions(visit, ASSIGNEE)).containsExactly(
                VisitNextActions.PROPOSE_TIME, VisitNextActions.CONFIRM_TIME);
        // The owner is not a scheduler: the two who meet plan.
        assertThat(actions(visit, OWNER)).isEmpty();
    }

    @Test
    @DisplayName("a confirmed visit is claimed inside its window, rescheduled only outside the horizon")
    void confirmedVisitReadsTheClock() {
        // Its window has opened: the claim is the act, the other doors are shut.
        Visit now = visit(NotraiusStatuses.VISIT_CONFIRMED,
                Instant.now().minus(5, ChronoUnit.MINUTES), null);
        now.setUserId(ASSIGNEE);
        assertThat(actions(now, VISITOR)).containsExactly(VisitNextActions.START);
        assertThat(actions(now, ASSIGNEE)).containsExactly(VisitNextActions.START);
        // An owner with a counterpart stamped does not claim the start.
        assertThat(actions(now, OWNER)).isEmpty();

        // Not yet: too early to claim, but far enough away to move or walk away.
        Visit later = visit(NotraiusStatuses.VISIT_CONFIRMED,
                Instant.now().plus(30, ChronoUnit.DAYS), null);
        later.setUserId(ASSIGNEE);
        assertThat(actions(later, VISITOR)).containsExactly(
                VisitNextActions.RESCHEDULE, VisitNextActions.CANCEL);
        assertThat(actions(later, ASSIGNEE)).containsExactly(VisitNextActions.RESCHEDULE);
    }

    @Test
    @DisplayName("the owner claims the start only when nobody was ever stamped")
    void ownerClaimsOnlyWithoutACounterpart() {
        Visit visit = visit(NotraiusStatuses.VISIT_CONFIRMED, Instant.now().minus(5, ChronoUnit.MINUTES), null);

        assertThat(actions(visit, OWNER)).containsExactly(VisitNextActions.START);
    }

    @Test
    @DisplayName("a claim is countersigned by the other side, and assessment opens with the start")
    void startedVisitOffersCountersignAndAssessment() {
        Visit claimedByVisitor = visit(NotraiusStatuses.VISIT_STARTED,
                Instant.now().minus(10, ChronoUnit.MINUTES), null);
        claimedByVisitor.setUserId(ASSIGNEE);
        claimedByVisitor.setStartedAt(Instant.now().minus(5, ChronoUnit.MINUTES));
        claimedByVisitor.setStartedBy(VISITOR);

        assertThat(actions(claimedByVisitor, ASSIGNEE)).containsExactly(
                VisitNextActions.CONFIRM_START, VisitNextActions.ASSESS);
        // The claimant cannot countersign their own claim; they may still assess.
        assertThat(actions(claimedByVisitor, VISITOR)).containsExactly(VisitNextActions.ASSESS);

        // Once countersigned there is nothing left to confirm.
        claimedByVisitor.setStartConfirmedAt(Instant.now().minus(4, ChronoUnit.MINUTES));
        assertThat(actions(claimedByVisitor, ASSIGNEE)).containsExactly(VisitNextActions.ASSESS);
    }

    @Test
    @DisplayName("a finished visit has no next act, and so does a caller who is no party")
    void terminalVisitsOfferNothing() {
        for (String status : List.of(NotraiusStatuses.VISIT_COMPLETED,
                NotraiusStatuses.VISIT_EXPIRED, NotraiusStatuses.VISIT_CANCELLED,
                NotraiusStatuses.VISIT_UNPAID)) {
            Visit visit = visit(status, Instant.now().plus(1, ChronoUnit.DAYS), null);
            visit.setUserId(ASSIGNEE);
            assertThat(actions(visit, VISITOR)).as(status).isEmpty();
            assertThat(actions(visit, OWNER)).as(status).isEmpty();
            assertThat(actions(visit, ASSIGNEE)).as(status).isEmpty();
        }
    }

    private List<String> actions(Visit visit, UUID callerId) {
        return VisitNextActions.of(visit, callerId, properties);
    }

    private Visit visit(String status, Instant from, Instant to) {
        Visit visit = new Visit();
        visit.setId(UUID.randomUUID());
        visit.setVisitorId(VISITOR);
        visit.setOwnerId(OWNER);
        visit.setStatus(status(status));
        Validity validity = new Validity();
        validity.setId(UUID.randomUUID());
        validity.setFromDate(from);
        validity.setToDate(to != null ? to : from.plus(1, ChronoUnit.HOURS));
        visit.setValidity(validity);
        return visit;
    }

    private Status status(String code) {
        Status status = new Status();
        status.setId(UUID.randomUUID());
        status.setCode(code);
        return status;
    }
}
