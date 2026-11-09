package com.notraius.modules.lease.state.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One notice, as its parties read it (PLAN 6.5).
 * <p>
 * {@code response} is the tenant's word — {@code null} while silence stands —
 * and {@code status} is the notice validity's own verdict (ISSUED, ACCEPTED,
 * REFUSED, ESCALATED, CANCELLED, SATISFIED). The two are deliberately separate:
 * silence is not a refusal until the machine's clock says so, and an accepted
 * grace can still escalate when its window ends with the cushion still short.
 * <p>
 * {@code windowEndsAt} is the validity row's end — the only deadline the engine
 * reads. {@code previousValidityToDate} is a VACATION notice's undo record: null
 * means the lease was open-ended before the stamp.
 */
@Getter
@Setter
public class LeaseNoticeResponse {

    private UUID id;
    private UUID leaseId;
    private String type;
    private String reason;
    private String response;
    private String status;
    private Instant issuedAt;
    private Instant windowEndsAt;
    private Instant respondedAt;
    private Instant previousValidityToDate;
    private Integer version;
    private Instant createdAt;
}
