package com.notraius.modules.lease.visit.visits.dto.request.visit;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * The owner's stamp (PLAN.md 5.2): the act names <em>a human</em>, it does not
 * accept on anyone's behalf. The visit moves REQUESTED → ASSIGNED, and that
 * human now holds the door: accept moves it to PLANNING, reject returns it to
 * REQUESTED for another stamp.
 * <p>
 * While the visit is still ASSIGNED — before acceptance — the stamp may be
 * rewritten (an agent handing the visit to a colleague): responsibility has not
 * attached yet. Once PLANNING begins the counterpart is fixed.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class VisitAssignRequest {

    /** The individual who becomes responsible; never the owner's own id. */
    @NotNull
    private UUID assignedUserId;
}
