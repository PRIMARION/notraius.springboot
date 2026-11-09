package com.notraius.database.entities.reference;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.primarion.database.entities.BaseEntity;

import java.util.UUID;

/**
 * A duration: a unit <em>type</em> and a value saying how many of it — HOUR
 * with value ten, MONTH with value one, MINUTE with value thirty (PLAN.md
 * Part 2).
 * <p>
 * This table never describes how often something repeats; it describes how
 * long one unit of something lasts. The commercial label (MONTHLY, NIGHTLY)
 * belongs to the pricing's own type, and the duration is the physics that
 * gives it a body.
 * <p>
 * <b>Identity is (type, value)</b>: HOUR + 10 is one row, and a second
 * creation is refused — the database enforces it with a unique constraint so
 * a concurrent pair of writers cannot both win.
 * <p>
 * <b>The type is immutable.</b> It is half of the identity; a duration bound
 * to a different unit is a different duration, so a mistake is corrected by
 * deleting the unused row and creating the right one.
 */
@Entity
@Table(name = "durations", schema = "notraius",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_durations_type_value", columnNames = {"type_id", "value"}),
        indexes = {
                @Index(name = "idx_durations_type_id", columnList = "type_id"),
                @Index(name = "idx_durations_status_id", columnList = "status_id")
        })
@Getter
@Setter
public class Duration extends BaseEntity {

    /** The unit kind — a type under the DURATION_UNIT entity. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "type_id", nullable = false)
    private Type type;

    /**
     * How many of {@link #type}. Nullable in the schema (a drafting
     * placeholder); every value that exists is ≥ 1 — enforced by the CHECK
     * constraint and by the service for creation and update.
     */
    @Column(name = "value")
    private Integer value;

    /** ACTIVE or RETIRED. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "status_id", nullable = false)
    private Status status;

    /** Creator of record (durations are user-created, Part 2). */
    @Column(name = "created_by")
    private UUID createdBy;
}
