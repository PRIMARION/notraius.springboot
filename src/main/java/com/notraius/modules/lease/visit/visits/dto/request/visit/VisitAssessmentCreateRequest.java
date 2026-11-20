package com.notraius.modules.lease.visit.visits.dto.request.visit;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Map;

/**
 * One party's judgment of the other (PLAN.md 5.3).
 * <p>
 * The axis vocabulary is the reputation host's, not this module's: the caller
 * sends the codes it was shown, and the host validates that every configured
 * axis is present and inside its range. notraius forwards the judgment — it never
 * invents a scale of its own.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class VisitAssessmentCreateRequest {

    /** One score per axis, keyed by the host's axis code. */
    @NotEmpty
    private Map<String, BigDecimal> axisScores;

    /** Free text the assessor leaves with the judgment. */
    @Size(max = 2000)
    private String reason;
}
