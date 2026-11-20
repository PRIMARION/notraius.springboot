package com.notraius.modules.lease.visit.visits.dto.request.visit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Staff's decision on a disputed visit (PLAN.md 5.5): the money goes one way.
 * <p>
 * {@code REFUND} returns the whole custody to the visitor; {@code RELEASE}
 * conveys the born split to the property owner. There is no third decision and
 * no partial one — the plan's module knows two whole outcomes, not a slider —
 * and no reversal of a movement that already happened, because money history is
 * append-only.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class VisitResolutionRequest {

    /** REFUND or RELEASE. */
    @NotBlank
    private String decision;

    /** Why staff decided it — recorded on the visit's trail, for the next reader. */
    @Size(max = 500)
    private String note;
}
