package com.notraius.database.entities.lease;

import com.notraius.database.entities.reference.Type;
import com.notraius.database.entities.reference.Validity;
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

import java.time.Instant;
import java.util.UUID;

/**
 * One notice of the lease's immune system (DATABASE.txt 5.6 + PLAN 6.5).
 * <p>
 * <b>Its own primary key.</b> A lease spawns a parade of notices across its life
 * — the deposit warning, the vacation, the reconversion, and the cycles a
 * landlord's override reopens — so the row belongs to the lease many-to-one,
 * never the other way round.
 * <p>
 * <b>The validity row is the clock.</b> The notice holds a {@link Validity} and
 * that row's end instant is its deadline; there is no {@code expires_at} column
 * and no {@code status_id}, because either would duplicate what the validity
 * already says. The notice's state is read from {@link #response} and that
 * window: issued (no response, window open), answered, or expired by silence.
 * <p>
 * <b>{@code previousValidityToDate} is the VACATION stamp's undo.</b> Issuing a
 * vacation sets the lease's validity end to the notice's expiry (the earlier of
 * that and the lease's prior end), and the landlord's cancellation restores
 * exactly what it overwrote — the original end where there was one, <em>null
 * again where it was null</em>, the open-ended lease returning to open-ended.
 */
@Entity
@Table(name = "notices", schema = "lease",
        indexes = {
                @Index(name = "idx_lease_notices_lease_id", columnList = "lease_id"),
                @Index(name = "idx_lease_notices_type_id", columnList = "type_id")
        })
@Getter
@Setter
public class LeaseNotice extends BaseEntity {

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "lease_id", nullable = false)
    private Lease lease;

    /** DEPOSIT, VACATION, RECONVERSION, LEASE_END or EVICTION. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "type_id", nullable = false)
    private Type type;

    @Column(columnDefinition = "text")
    private String reason;

    /** ACCEPT, REFUSE or CANCELLED; null means silence, which the vigil acts on. */
    @Column(length = 20)
    private String response;

    /** The notice's clock: its validity's end is when the machine stops waiting. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "validity_id", nullable = false)
    private Validity validity;

    /** The lease end the VACATION stamp overwrote — null when it was open-ended. */
    @Column(name = "previous_validity_to_date")
    private Instant previousValidityToDate;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "created_by")
    private UUID createdBy;
}
