package com.notraius.database.entities.lease.lease.records;

import com.notraius.database.entities.lease.reference.status.Status;
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

import java.time.Instant;
import java.util.UUID;
import com.notraius.database.entities.lease.lease.records.Lease;

/**
 * The end-of-life money walk of one tenancy (DATABASE.txt 5.7 + PLAN 6.7).
 * <p>
 * <b>One settlement per lease</b> — the unique constraint is the law.
 * <p>
 * <b>Its birth frees the property.</b> The instant an ACTIVE lease ends the
 * settlement is auto-generated and the property passes to DISABLED in that same
 * act: deliberately dark, because a vacated place needs cleaning and an owner's
 * eyes, and <em>freed</em>, because the settlement is a money walk, not
 * occupancy. Nothing inside this row ever gates the owner's "make it available"
 * click.
 * <p>
 * <b>{@code deadline} is the move-out week's clock.</b> The landlord has one week
 * to upload the AFTER_MOVE_OUT conditions and list the repairs, and the billed
 * sides each have their fortnight to answer what is billed — never what is held,
 * because custody needs no fortnight to pay late (6.7). A settlement that misses
 * its clock turns OVERDUE, which is the dispute door's jurisdiction, not a dead
 * end.
 * <p>
 * <b>Confirmation completes the lease.</b> Everything a settlement moved stays
 * moved; the row's confirmation is the act that writes COMPLETED on the lease.
 */
@Entity
@Table(name = "settlements", schema = "lease",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_lease_settlements_lease", columnNames = "lease_id"),
        indexes = @Index(name = "idx_lease_settlements_status_deadline",
                columnList = "status_id, deadline"))
@Getter
@Setter
public class LeaseSettlement extends BaseEntity {

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "lease_id", nullable = false)
    private Lease lease;

    /** OPEN, OVERDUE or CONFIRMED. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "status_id", nullable = false)
    private Status status;

    /**
     * The billed sides' fortnight (6.7): the debts' own deadline and the clock
     * whose lapse turns the settlement OVERDUE into the dispute door.
     */
    @Column(nullable = false)
    private Instant deadline;

    /**
     * The landlord's move-out week: his window to upload the AFTER_MOVE_OUT
     * conditions and list the repairs. Two clocks, two columns — one deadline
     * cannot honestly govern both what is held and what is billed.
     */
    @Column(name = "move_out_deadline")
    private Instant moveOutDeadline;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "confirmed_by")
    private UUID confirmedBy;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "created_by")
    private UUID createdBy;
}
