package com.notraius.modules.core.common;

import java.time.Instant;

/**
 * The estate's window law, in one place: does a validity window contain an
 * instant, and what status does the clock make of it.
 * <p>
 * <b>Why a class.</b> Three services had grown their own copy of this rule —
 * {@code PricingService} (deriving {@code EXPIRED} for a price), {@code Terms}
 * (deriving it for a rule selection) and {@code LeaseAgreementService} (refusing
 * a price whose window has passed) — and two of the three disagreed about the
 * start of the window: one asked "has it begun and not ended", the other only
 * "has it ended". A rule selected for next season therefore read {@code ACTIVE}
 * today. The plan states the rule once (Part 4.2: the window moves the status;
 * while no window contains the present instant the row stands {@code EXPIRED}),
 * so the code states it once.
 * <p>
 * <b>Half-open.</b> {@code [from, to)}: the end instant belongs to the next
 * window, so a price change at midnight has no instant claimed twice and none
 * claimed by nobody (PLAN 4.2's handover). An open end ({@code to == null}) never
 * expires; a window with no start cannot answer at all — an unstamped validity is
 * not a promise about now.
 */
public final class Windows {

    private Windows() {
    }

    /**
     * Whether {@code now} falls inside {@code [from, to)}.
     *
     * @param from the window's start; {@code null} never answers (nothing was promised)
     * @param to   the window's end; {@code null} means permanent
     */
    public static boolean contains(Instant from, Instant to, Instant now) {
        return from != null
                && !from.isAfter(now)
                && (to == null || to.isAfter(now));
    }

    /**
     * The status a record reads as at {@code now}: the clock speaks first.
     * <p>
     * A window that does not contain {@code now} reads {@code expiredCode}
     * whatever the owner last did — expiry is the one fact an earlier act cannot
     * undo. Inside the window the stored act is the answer, so {@code PAUSED}
     * never masquerades as the clock's verdict.
     *
     * @param storedCode  what the owner set (LIVE, PAUSED, ACTIVE, …)
     * @param expiredCode the word this vocabulary uses for "answers no instant now"
     */
    public static String statusIn(String storedCode, String expiredCode,
                                  Instant from, Instant to, Instant now) {
        if (!contains(from, to, now)) {
            return expiredCode;
        }
        return storedCode;
    }
}
