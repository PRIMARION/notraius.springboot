package com.notraius.modules.visit.visits.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A reschedule's first move (PLAN.md 5.4): the new time, offered to the other
 * party for the same confirmation cycle a first planning gets. The end is
 * optional — omitted, the pricing's own duration decides it, so a caller cannot
 * quietly shorten a visit by leaving the end out.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class VisitRescheduleRequest {

    @NotNull
    private Instant fromDate;

    private Instant toDate;
}
