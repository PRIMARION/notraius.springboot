package com.notraius.modules.lease.asset.terms.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A rule the property holds: which rule, for how long, and in what state.
 * <p>
 * The rule's title, text and visibility belong to {@code /rules} — the client
 * has {@code ruleId}. A selection is a link with a window, so a link is what it
 * answers.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RuleSelectionResponse {

    private UUID id;

    private UUID propertyId;

    private UUID ruleId;

    private Instant fromDate;

    /** Null means the selection is permanent. */
    private Instant toDate;

    /** ACTIVE while the window contains now; EXPIRED once it is past. */
    private String statusCode;

    /**
     * The act the row states (its stored status), beside the clock's verdict above.
     * <p>
     * {@code statusCode} is derived, so a snapshot that froze it froze a moment:
     * a revision read back later would answer with the clock's verdict of the day
     * it was written. Publishing the act lets a historical read re-derive the
     * status at the instant of the read — the same law the live door applies — so
     * one version cannot read ACTIVE through the list and EXPIRED through
     * {@code ?version=N}. Snapshots written before this field existed carry only
     * the derived word; the reader falls back to ACTIVE, the only act a selection
     * is ever born with.
     */
    private String storedStatusCode;

    private Integer version;

    private Instant createdAt;
}
