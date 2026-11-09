package com.notraius.modules.visit.visits.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A planning move (PLAN.md 5.2): propose the visit's time, or counter the
 * counterpart's proposal with your own.
 * <p>
 * The same act serves both directions — the turn rule decides whether you are
 * opening, countering, or refining your own proposal inside the re-edit window.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class VisitTimeProposalRequest {

    @NotNull
    private Instant fromDate;

    /** Null means "the pricing's duration", as at request time. */
    private Instant toDate;
}
