package com.notraius.database.entities.lease.visit;

import com.notraius.database.entities.lease.reference.status.Status;
import com.notraius.database.entities.lease.reference.amenity.Validity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.primarion.database.entities.BaseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import com.notraius.database.entities.lease.visit.VisitPricing;

/**
 * One paid visit request (DATABASE.txt 4.2 + the plan's money deltas).
 * <p>
 * <b>The module's first data rule lives in this class:</b> {@code visitorId} is
 * the prospect who pays, {@code userId} is the operational counterpart attached
 * to the visit (the assignee once 5.2 stamps one), and {@code ownerId} is the
 * property owner — the only party settlement ever pays. Those are three
 * different people on purpose; collapsing them is the mistake the plan warns
 * about twice.
 * <p>
 * <b>The snapshots are the charge, not a cache.</b> {@code amount} and
 * {@code currencyId} record what was actually taken. A pricing edited tomorrow
 * must never rewrite what a visitor already paid, and a settlement must never
 * have to reconstruct yesterday's number from today's rows.
 * <p>
 * <b>The payment's reference is this row's own id</b> (the operator's
 * uniqueness ruling). Pay deduplicates on (reference, currency), so one visit's
 * retries converge on its own charge while a second visit at the same property
 * — a different id — can never be folded into the first's money.
 */
@Entity
@Table(name = "visits", schema = "visit")
@Getter
@Setter
public class Visit extends BaseEntity {

    /** The prospect who requests and pays. */
    @Column(name = "visitor_id", nullable = false)
    private UUID visitorId;

    /** The operational counterpart — the assignee, never the beneficiary. */
    @Column(name = "user_id")
    private UUID userId;

    /** The property owner at request time: the beneficiary snapshot. */
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "pricing_id", nullable = false)
    private VisitPricing pricing;

    /** The scheduled slot; PLANNING edits it, the boundary belongs to the start. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "validity_id", nullable = false)
    private Validity validity;

    /** The charge snapshot — what the visitor actually paid. */
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    /** The property's currency, snapshotted with the charge. */
    @Column(name = "currency_id", nullable = false)
    private UUID currencyId;

    /** Pay's payment id; null until funding is definitively known to have happened. */
    @Column(name = "payment_id")
    private UUID paymentId;

    /** The property owner's split — the future release/refund address. */
    @Column(name = "payment_split_id")
    private UUID paymentSplitId;

    /**
     * The STARTED claim (PLAN 5.3) — who claimed the visit is happening, and
     * when. The claim is a promise the other party countersigns; the audit entry
     * of the same act is the official evidence, this column the sweeps' index.
     */
    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "started_by")
    private UUID startedBy;

    /** The countersignature. Null past the configured window means the claim lapsed. */
    @Column(name = "start_confirmed_at")
    private Instant startConfirmedAt;

    @Column(name = "start_confirmed_by")
    private UUID startConfirmedBy;

    /** When COMPLETED landed — by the mutual confirm or by the hour. */
    @Column(name = "completed_at")
    private Instant completedAt;

    /**
     * The content revision the client sees: birth is one, every accepted edit
     * or transition increments it. Deliberately <b>not</b> a concurrency token —
     * two requests that read revision 3 both write 4, and the revision cannot
     * tell which edit was lost.
     */
    @Column(nullable = false)
    private Integer version;

    /**
     * Hibernate's concurrency token, and the only column that makes the
     * revision honest: every UPDATE carries {@code AND lock_version = <loaded>},
     * so a write racing another write affects no row and the flush raises
     * {@code OptimisticLockException} — answered by the shared handler as 409
     * CONFLICT with a retry hint. Internal: never returned to clients. See
     * V37 for why it is not the {@code version} column above.
     */
    @jakarta.persistence.Version
    @Column(name = "lock_version", nullable = false)
    private Integer lockVersion;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "status_id", nullable = false)
    private Status status;

    @Column(name = "created_by")
    private UUID createdBy;
}
