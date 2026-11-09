package com.notraius.modules.visit.visits.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A filed judgment, as the visit's page shows it: who judged, on what axes, and
 * when — the host's record, mirrored only for reading.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class VisitAssessmentResponse {

    private UUID id;
    private UUID authorId;
    private String reason;
    private Map<String, BigDecimal> axisScores;
    private BigDecimal score;
    private Instant createdAt;
}
