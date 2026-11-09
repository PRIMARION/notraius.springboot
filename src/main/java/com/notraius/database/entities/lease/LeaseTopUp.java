package com.notraius.database.entities.lease;

import com.notraius.database.entities.reference.Status;
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
import java.util.UUID;

/**
 * One top-up funding act (PLAN 6.3's restore law).
 * <p>
 * <b>Its own reference, never the lease's.</b> The birth matrix's reference is
 * the lease id; a top-up that reused it would be folded by pay's
 * {@code (reference, currency)} dedupe into the live birth funding and silently
 * restore nothing. So each top-up is its own event with its own id — persisted
 * <em>before</em> the call leaves, so a retried top-up finds its funding instead
 * of charging the tenant twice, and an answer that never arrives is an ask that
 * repeats, never a compensation guessed in the dark.
 * <p>
 * The row names the deposit it reconstitutes and the units it restores; the
 * money's own home is still pay's escrows, mirrored per split in
 * {@link LeaseDepositSplit}. A top-up may overshoot the standard — the money is
 * real custody, and what the standard governs is the grace's satisfaction, never
 * a ceiling on how much a tenant may park.
 */
@Entity
@Table(name = "topups", schema = "lease",
        indexes = {
                @Index(name = "idx_lease_topups_lease_id", columnList = "lease_id"),
                @Index(name = "idx_lease_topups_deposit_id", columnList = "deposit_id"),
                @Index(name = "idx_lease_topups_status_id", columnList = "status_id")
        })
@Getter
@Setter
public class LeaseTopUp extends BaseEntity {

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "lease_id", nullable = false)
    private Lease lease;

    /** The standard whose counter this funding climbs. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "deposit_id", nullable = false)
    private LeaseDeposit deposit;

    @Column(precision = 15, scale = 2, nullable = false)
    private BigDecimal amount;

    /** Whole units restored (a UNITS standard's counter climbs by this). */
    @Column(nullable = false)
    private Integer units;

    /** Pay's payment id once the funding stands; null while its outcome is unknown. */
    @Column(name = "payment_id")
    private UUID paymentId;

    /** PENDING (created, call may be in flight), FUNDED, FAILED. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "status_id", nullable = false)
    private Status status;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "created_by")
    private UUID createdBy;
}
