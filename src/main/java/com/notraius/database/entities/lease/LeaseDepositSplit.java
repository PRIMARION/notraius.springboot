package com.notraius.database.entities.lease;

import com.notraius.database.entities.reference.Status;
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

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One born escrow, mirrored locally (PLAN 6.0/6.3/6.4).
 * <p>
 * <b>One split is atomic.</b> It maps to one unit of the policy's thing — a
 * month of advance, or the whole of an AMOUNT-valued standard — and has exactly
 * two fates: fully released or fully refunded, whole in one act, never drawn
 * down. This row is the ledger that lets the machine know which escrows still
 * stand, in which order they were born, and what happened to each one, without
 * re-reading pay's whole payment on every sweep tick. Pay remains the money's
 * owner: {@code paymentId}/{@code splitId} are the two addresses its custody
 * release and refund doors take.
 * <p>
 * The {@code referenceId} is the split's own <b>(EXTERNAL, reference)</b>
 * address — the operator's per-month vocabulary that lets pay's analytics
 * reconcile one month of advance. It is not an EXTERNAL flow: the lease's money
 * is INTERNAL all the way, wallet into escrow (6.0).
 */
@Entity
@Table(name = "deposit_splits", schema = "lease",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_lease_deposit_splits_pay_split", columnNames = {"payment_id", "split_id"}),
        indexes = {
                @Index(name = "idx_lease_deposit_splits_lease_id", columnList = "lease_id"),
                @Index(name = "idx_lease_deposit_splits_deposit_id", columnList = "deposit_id")
        })
@Getter
@Setter
public class LeaseDepositSplit extends BaseEntity {

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "lease_id", nullable = false)
    private Lease lease;

    /** The deposit standard this escrow stands for. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "deposit_id", nullable = false)
    private LeaseDeposit deposit;

    /** Pay's payment id — the release/refund door's first address. */
    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    /** Pay's split id — the second address. */
    @Column(name = "split_id", nullable = false)
    private UUID splitId;

    /** The split's own reference: which unit of which standard this escrow pays for. */
    @Column(name = "reference_id", nullable = false)
    private UUID referenceId;

    /** The frozen figure, in the property's currency. Whole or nothing. */
    @Column(precision = 15, scale = 2, nullable = false)
    private BigDecimal amount;

    /** Birth order — the FIFO tie-breaker, 1-based, never renumbered. */
    @Column(nullable = false)
    private Integer sequence;

    /** HELD, RELEASED or REFUNDED. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "status_id", nullable = false)
    private Status status;
}
