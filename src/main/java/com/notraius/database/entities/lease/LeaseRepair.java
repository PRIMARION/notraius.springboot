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

import java.util.UUID;

/**
 * One repair listed against a move-out condition (DATABASE.txt 5.5 + PLAN 6.7).
 * <p>
 * <b>Repairs are only ever created by the landlord</b>, so the row needs no
 * created-by machinery: the landlord-side counterpart is its sole author by
 * construction. That is exactly why <em>the payer must be declared</em> on the
 * repair's breakdown {@code user_id} rather than inferred from the creator — the
 * landlord authors repairs charged to the tenant just as readily as repairs
 * charged to himself, and that column is the only figure that tells them apart.
 * <p>
 * The money rule follows: repairs the landlord owes are never in the settlement's
 * balance sheet — they are his own works, outside it; only tenant-chargeable
 * repairs enter the debts.
 */
@Entity
@Table(name = "repairs", schema = "lease",
        indexes = {
                @Index(name = "idx_lease_repairs_condition_id", columnList = "condition_id"),
                @Index(name = "idx_lease_repairs_lease_id", columnList = "lease_id")
        })
@Getter
@Setter
public class LeaseRepair extends BaseEntity {

    /** The move-out report the repair was listed against. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "condition_id", nullable = false)
    private LeaseCondition condition;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "lease_id", nullable = false)
    private Lease lease;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    /** PENDING until the tenant confirms it; CONFIRMED once both are ready. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "status_id", nullable = false)
    private Status status;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "created_by")
    private UUID createdBy;
}
