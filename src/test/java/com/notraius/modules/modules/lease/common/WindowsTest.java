package com.notraius.modules.modules.lease.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.primarion.database.entities.lifecycle.ValidityView;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import com.notraius.modules.core.core.common.Windows;

/**
 * The window law every derived status rides on: half-open, and a window that does
 * not contain now reads the expired word whatever the stored act says.
 */
class WindowsTest {

    private static final Instant NOW = Instant.parse("2026-03-15T12:00:00Z");

    @Test
    @DisplayName("a window is [from, to): its start belongs to it, its end belongs to the next one")
    void halfOpen() {
        Instant from = NOW.minus(1, ChronoUnit.DAYS);
        Instant to = NOW.plus(1, ChronoUnit.DAYS);

        assertThat(Windows.contains(from, to, NOW)).isTrue();
        assertThat(Windows.contains(NOW, to, NOW)).isTrue();
        assertThat(Windows.contains(from, NOW, NOW)).isFalse();
        assertThat(Windows.contains(from, to, from)).isTrue();
    }

    @Test
    @DisplayName("an open end never expires; an unstamped start never answers")
    void openEndAndMissingStart() {
        assertThat(Windows.contains(NOW.minus(365, ChronoUnit.DAYS), null, NOW)).isTrue();
        assertThat(Windows.contains(null, NOW.plus(1, ChronoUnit.DAYS), NOW)).isFalse();
        assertThat(Windows.contains(null, null, NOW)).isFalse();
    }

    @Test
    @DisplayName("the host law and the shared ValidityView law are the same law, case for case")
    void agreesWithSharedValidityView() {
        // Windows is the host's static form of the estate rule shared's
        // ValidityView#isLive states for the ports (event scan, consent, the
        // subscription windows). Two implementations are deliberate — one takes
        // instants, one takes a row — so they are pinned to agree here: an edit to
        // either that changes a boundary fails this test instead of drifting.
        Instant day = NOW.plus(1, ChronoUnit.DAYS);
        Instant[][] windows = {
                {NOW.minus(1, ChronoUnit.DAYS), NOW.plus(1, ChronoUnit.DAYS)},
                {NOW, NOW.plus(1, ChronoUnit.DAYS)},
                {NOW.minus(1, ChronoUnit.DAYS), NOW},
                {NOW.minus(1, ChronoUnit.DAYS), null},
                {null, NOW.plus(1, ChronoUnit.DAYS)},
                {null, null},
                {day, day.plus(1, ChronoUnit.DAYS)},
                {NOW.plus(1, ChronoUnit.DAYS), NOW.minus(1, ChronoUnit.DAYS)},
        };

        for (Instant[] window : windows) {
            boolean host = Windows.contains(window[0], window[1], NOW);
            boolean shared = new ValidityView(UUID.randomUUID(), "ACTIVE", window[0], window[1])
                    .isLive(NOW);
            assertThat(host)
                    .as("from=%s to=%s", window[0], window[1])
                    .isEqualTo(shared);
        }
    }

    @Test
    @DisplayName("the clock speaks first: outside the window the stored act is not the answer")
    void statusIn() {
        Instant past = NOW.minus(30, ChronoUnit.DAYS);
        Instant ended = NOW.minus(1, ChronoUnit.DAYS);

        assertThat(Windows.statusIn("LIVE", "EXPIRED", past, ended, NOW)).isEqualTo("EXPIRED");
        assertThat(Windows.statusIn("LIVE", "EXPIRED", past, null, NOW)).isEqualTo("LIVE");
        assertThat(Windows.statusIn("PAUSED", "EXPIRED", past, null, NOW)).isEqualTo("PAUSED");
        assertThat(Windows.statusIn("LIVE", "EXPIRED", null, null, NOW)).isEqualTo("EXPIRED");
    }
}
