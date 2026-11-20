package com.notraius.database.entities.lease.lease.records;

import com.notraius.database.entities.lease.reference.amenity.Entities;
import com.notraius.database.entities.lease.reference.status.Status;
import com.notraius.database.entities.lease.reference.status.Type;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.primarion.database.entities.BaseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import com.notraius.database.entities.lease.lease.records.Lease;

/**
 * One money row of the lease's calendar (DATABASE.txt 5.3 + PLAN 6.2/6.3).
 * <p>
 * <b>A breakdown is the lease's universal money-row.</b> Rent and whatever else
 * the cadence bills are INSTALLMENT rows generated from the validity; a repair's
 * charge is a REPAIR row whose {@code userId} names <em>who pays</em> (6.7's
 * payer rule, never derived from authorship); the settlement's own debts are
 * SETTLEMENT rows. The {@code (entity, reference)} pair is the row's provenance
 * — the record it pays for — and {@code userId} is the recipient for an
 * installment, the payer for a repair.
 * <p>
 * <b>The deadline is not optional.</b> OVERDUE is the clock's verdict on it, and
 * the vigil, the sweep, the payment route and the notice engine all read the
 * same date rollover — "passed" is one law, not a wall-clock intuition (6.2).
 * <p>
 * <b>A DEPOSITED row stores the escrow's own identity</b> ({@code escrowPaymentId}
 * /{@code escrowSplitId}): the ledger thread that ties "this rent" to "that
 * custody" so any later reading reconstructs the consumption line by line (6.3).
 * <p>
 * {@code periodIndex} is the calendar's own ordering and is unique per lease:
 * the schedule never bills the same period twice, and a re-derived schedule
 * (6.5's cancellation restore) can tell which index it is rebuilding.
 */
@Entity
@Table(name = "breakdowns", schema = "lease",
        indexes = {
                @Index(name = "idx_lease_breakdowns_lease_id", columnList = "lease_id"),
                @Index(name = "idx_lease_breakdowns_reference", columnList = "entity_id, reference_id"),
                @Index(name = "idx_lease_breakdowns_status_id", columnList = "status_id"),
                @Index(name = "idx_lease_breakdowns_status_deadline", columnList = "status_id, deadline")
        })
@Getter
@Setter
public class LeaseBreakdown extends BaseEntity {

    /** The record this money row pays for (LEASE_BREAKDOWNS, LEASE_REPAIRS, …). */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "entity_id", nullable = false)
    private Entities entity;

    @Column(name = "reference_id", nullable = false)
    private UUID referenceId;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "lease_id", nullable = false)
    private Lease lease;

    @Column(length = 255)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(precision = 15, scale = 2, nullable = false)
    private BigDecimal amount;

    /** INSTALLMENT, REPAIR or SETTLEMENT. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "type_id", nullable = false)
    private Type type;

    /** PENDING, SETTLED, DEPOSITED, OVERDUE or CANCELLED. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "status_id", nullable = false)
    private Status status;

    /** The recipient of an installment; the declared payer of a repair. */
    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false)
    private Instant deadline;

    @Column(name = "period_start")
    private Instant periodStart;

    @Column(name = "period_end")
    private Instant periodEnd;

    /** The lease period this row bills, 1-based; null for one-off charges. */
    @Column(name = "period_index")
    private Integer periodIndex;

    /** The consumed escrow, when the row was satisfied from custody (6.3). */
    @Column(name = "escrow_payment_id")
    private UUID escrowPaymentId;

    @Column(name = "escrow_split_id")
    private UUID escrowSplitId;

    /** When the bill was answered — a payment, a cash confirmation or custody. */
    @Column(name = "settled_at")
    private Instant settledAt;

    /** One installment, one auto-assessment (6.3): the second pass never judges again. */
    @Column(name = "assessment_filed", nullable = false)
    private boolean assessmentFiled;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "created_by")
    private UUID createdBy;
}
