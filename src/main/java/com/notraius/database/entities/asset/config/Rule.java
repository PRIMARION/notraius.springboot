package com.notraius.database.entities.asset.config;

import com.notraius.database.entities.lease.reference.status.Status;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.primarion.database.entities.BaseEntity;

import java.util.UUID;
import com.notraius.database.entities.asset.config.RuleVisibility;

/**
 * A house rule (PLAN.md 3.1): a title, a description, a visibility its creator
 * chooses at birth, and a version.
 * <p>
 * <b>The row only remembers the newest truth.</b> Every edit bumps
 * {@link #version} and writes a before/after entry to the log chain <i>in the
 * same transaction</i>, so a version can never exist without its history or a
 * history without its version; birth is version one and is itself the first
 * entry, which gives replay a base. "The rule as of version three" is answered
 * from that chain, never from this row.
 * <p>
 * <b>In use means locked, not frozen.</b> Once any live property selection
 * references the rule, its status may no longer move to DISABLED — but editing
 * stays allowed, because properties reference the rule rather than a snapshot
 * and see the new version through their own selections' windows. When the last
 * selection goes away the lock lifts, so disabling is reversible and the lock
 * is a live count, not a ratchet. There is no delete path at all: deletion
 * would break reconstitution.
 */
@Entity
@Table(name = "rules", schema = "notraius",
        indexes = {
                @Index(name = "idx_rules_user_id", columnList = "user_id"),
                @Index(name = "idx_rules_status_id", columnList = "status_id"),
                @Index(name = "idx_rules_visibility", columnList = "visibility")
        })
@Getter
@Setter
public class Rule extends BaseEntity {

    /** The creator — sole writer, and sole reader while the rule is PRIVATE. */
    @Column(name = "user_id", nullable = false)
    private UUID creatorId;

    @Column(nullable = false, length = 100)
    private String title;

    /** NOT NULL: a rule with nothing to say is not a rule. */
    @Column(nullable = false, columnDefinition = "text")
    private String description;

    /** Chosen at birth; only the creator may flip it, and only safely. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private RuleVisibility visibility;

    /** Birth is version one; every edit increments it. */
    @Column(nullable = false)
    private Integer version;

    /** ACTIVE, DISABLED or RETIRED — see the migration for the vocabulary. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "status_id", nullable = false)
    private Status status;

    /** Who performed the last write; equals the creator by construction. */
    @Column(name = "created_by")
    private UUID createdBy;

    /**
     * The search column of V35: the database owns it — a BEFORE INSERT OR
     * UPDATE trigger rebuilds it from this row's prose — so it is mapped
     * read-only and exists only so JPQL can hand it to
     * {@code tsvector_matches(vector, :search)}. The application never reads
     * its contents and never writes it.
     */
    @Column(name = "vector", insertable = false, updatable = false, columnDefinition = "tsvector")
    private String vector;
}
