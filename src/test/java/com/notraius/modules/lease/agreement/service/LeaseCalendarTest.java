package com.notraius.modules.lease.agreement.service;

import com.notraius.database.entities.reference.Duration;
import com.notraius.database.entities.reference.Type;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The calendar's four laws (PLAN 6.2): an anniversary never drifts, a deadline
 * is never born in the past, a period never outlives the grant, and an
 * open-ended validity generates a bounded horizon.
 */
class LeaseCalendarTest {

    private static final Instant START = at(2026, 1, 31, 12);
    private static final Instant MID = at(2026, 6, 1, 0);

    @Test
    @DisplayName("a short month clamps the anniversary, and the next period returns to the 31st")
    void anniversaryNeverDrifts() {
        List<LeaseAgreementService.Period> periods = LeaseAgreementService.periods(START, at(2026, 4, 1, 0), MID,
                6, monthly(1), 1);

        assertThat(periods).hasSize(2);
        assertThat(periods.get(0).start()).isEqualTo(START);
        assertThat(periods.get(0).end()).isEqualTo(at(2026, 2, 28, 12));
        // Computed from the start, not the clamp: March returns to the 31st.
        assertThat(periods.get(1).start()).isEqualTo(at(2026, 2, 28, 12));
        assertThat(periods.get(1).end()).isEqualTo(at(2026, 3, 31, 12));
    }

    @Test
    @DisplayName("a deadline is never scheduled in the past: a slipped start is due now")
    void deadlineNeverPast() {
        Instant now = at(2026, 6, 15, 9);
        List<LeaseAgreementService.Period> periods = LeaseAgreementService.periods(
                at(2026, 6, 1, 0), null, now, 2, monthly(1), 1);

        assertThat(periods).hasSize(2);
        assertThat(periods.get(0).deadline()).isEqualTo(now);
        // A future period keeps its own start as the deadline.
        assertThat(periods.get(1).deadline()).isEqualTo(at(2026, 7, 1, 0));
    }

    @Test
    @DisplayName("no period is born beyond the validity's end")
    void noPeriodOutlivesTheGrant() {
        Instant to = at(2026, 3, 15, 0);
        List<LeaseAgreementService.Period> periods = LeaseAgreementService.periods(START, to, MID, 6,
                monthly(1), 1);

        assertThat(periods).hasSize(1);
        assertThat(periods.get(0).end()).isBeforeOrEqualTo(to);
    }

    @Test
    @DisplayName("an open-ended validity generates exactly its horizon")
    void openEndedHorizon() {
        List<LeaseAgreementService.Period> periods = LeaseAgreementService.periods(START, null, MID, 6,
                monthly(1), 1);

        assertThat(periods).hasSize(6);
        assertThat(periods.get(5).index()).isEqualTo(6);
    }

    @Test
    @DisplayName("the earlier-of law tolerates a null end, and plusMonths clamps")
    void earlierAndMonths() {
        Instant end = at(2026, 5, 31, 0);
        assertThat(LeaseAgreementService.earlier(end, null)).isEqualTo(end);
        assertThat(LeaseAgreementService.earlier(null, end)).isEqualTo(end);
        assertThat(LeaseAgreementService.earlier(end, at(2026, 2, 1, 0))).isEqualTo(at(2026, 2, 1, 0));
        assertThat(LeaseAgreementService.plusMonths(at(2026, 1, 31, 0), 1)).isEqualTo(at(2026, 2, 28, 0));
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private static Duration monthly(int count) {
        Type type = new Type();
        type.setCode("MONTH");
        Duration duration = new Duration();
        duration.setType(type);
        duration.setValue(count);
        return duration;
    }

    private static Instant at(int year, int month, int day, int hour) {
        return ZonedDateTime.of(year, month, day, hour, 0, 0, 0, ZoneOffset.UTC).toInstant();
    }
}
