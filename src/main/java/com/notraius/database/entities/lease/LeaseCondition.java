package com.notraius.database.entities.lease;

import com.notraius.database.entities.reference.Status;
import com.notraius.database.entities.reference.Type;
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
 * One condition report of a tenancy (DATABASE.txt 5.4 + PLAN 6.2/6.7).
 * <p>
 * The tenant reports the premises as received ({@code BEFORE_MOVE_IN}) and the
 * landlord confirms them — "yes, those really were the things" — and that
 * confirmation is what parks the lease into ACTIVE. At the other end the
 * landlord reports the premises as returned ({@code AFTER_MOVE_OUT}) and the
 * tenant validates. The versioned trail is the evidence the end-of-life's
 * adjudications stand on.
 * <p>
 * Documents ride the storage module's {@code (entity, reference)} pair; this row
 * carries the enumerated state in {@code notes} so the report is a row rather
 * than a convention.
 */
@Entity
@Table(name = "conditions", schema = "lease",
        indexes = {
                @Index(name = "idx_lease_conditions_lease_id", columnList = "lease_id"),
                @Index(name = "idx_lease_conditions_status_id", columnList = "status_id")
        })
@Getter
@Setter
public class LeaseCondition extends BaseEntity {

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "lease_id", nullable = false)
    private Lease lease;

    /** BEFORE_MOVE_IN or AFTER_MOVE_OUT. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "type_id", nullable = false)
    private Type type;

    /** PENDING until the counterpart confirms it. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "status_id", nullable = false)
    private Status status;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(nullable = false)
    private Integer version;

    /** Who authored the report — the tenant for move-in, the landlord for move-out. */
    @Column(name = "created_by")
    private UUID createdBy;
}
