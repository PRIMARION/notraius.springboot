package com.notraius.common.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One revision a record's audit chain holds — the entry {@code /revisions}
 * contributes, and the shape {@code ?version=N} can always answer for.
 * <p>
 * <b>Only what discovery needs.</b> The number, when it became the newest truth,
 * and whether it is the row's current one. Who made the write and under which
 * activity are deliberately absent: the chain records them, but publishing an
 * actor id on a door that (for policies) is public would open a second surface
 * over staff identities that no plan decision asked for. A caller who needs the
 * authorisation story reads the audit trail the estate already exposes to its
 * operators.
 * <p>
 * {@code recordedAt} is the instant of the newest entry stating that revision,
 * which is exactly the entry {@code ?version=N} serves: the listing and the read
 * can never disagree about when a revision happened.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RevisionSummaryResponse {

    /** The revision number — the value {@code ?version=} accepts. */
    private int version;

    /** When this revision became the record's newest truth. */
    private Instant recordedAt;

    /**
     * Whether this is the version the row states now. The live read is the
     * authoritative answer for it; earlier ones come from the chain.
     */
    private boolean current;
}
