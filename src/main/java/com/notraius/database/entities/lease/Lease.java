package com.notraius.database.entities.lease;

import com.notraius.database.entities.asset.Pricing;
import com.notraius.database.entities.reference.Duration;
import com.notraius.database.entities.reference.Status;
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

import java.util.UUID;

/**
 * One tenancy (DATABASE.txt 5.1 + PLAN 6.1's money deltas).
 * <p>
 * <b>Three identities, three meanings</b> (PLAN 6.9): {@code userId} is the
 * tenant — the party who pays, is refunded and receives the notices;
 * {@code ownerId} is the beneficiary snapshot taken from the property at
 * funding time; the "landlord-side counterpart" is the recipient named on the
 * installments' own {@code user_id} rows, never inferred from either field
 * here. Born custody releases to the beneficiaries its payment's splits named,
 * whatever the deed says today (6.4).
 * <p>
 * <b>The lease binds to a pricing, not a property</b> (6.0). The pricing
 * supplies the cadence (its duration row), the rent, {@code checkable}, and —
 * through the property — the currency and the state whose policy chain prices
 * the deposits. Nothing in the creation flow needs a settings screen, and the
 * hotel case is the same machine with different data.
 * <p>
 * <b>CANCELLED is a pre-commitment word only.</b> A lease that was confirmed or
 * has lived has no whole matrix left to return; it ends through the
 * settlement's door (6.7), never by cancellation.
 */
@Entity
@Table(name = "leases", schema = "lease",
        indexes = {
                @Index(name = "idx_leases_user_id", columnList = "user_id"),
                @Index(name = "idx_leases_owner_id", columnList = "owner_id"),
                @Index(name = "idx_leases_pricing_id", columnList = "pricing_id"),
                @Index(name = "idx_leases_status_id", columnList = "status_id"),
                @Index(name = "idx_leases_validity_id", columnList = "validity_id")
        })
@Getter
@Setter
public class Lease extends BaseEntity {

    /** The tenant: pays the matrix, is refunded, receives the notices. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** The beneficiary snapshot — the property's owner at funding time. */
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    /** The commercial context: cadence, rent, checkable, property, currency. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "pricing_id", nullable = false)
    private Pricing pricing;

    /** Reference to the general-conditions document the parties accepted. */
    @Column(name = "terms_id")
    private UUID termsId;

    /** The tenancy window; a null end is the open-ended lease (6.5). */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "validity_id", nullable = false)
    private Validity validity;

    /** PENDING → CONFIRMED → ACTIVE → COMPLETED, plus CANCELLED/EXPIRED/DISPUTED. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "status_id", nullable = false)
    private Status status;

    /**
     * NATIVE or IMPORT (6.6). An imported lease carries no move-in conditions by
     * nature, so activation skips the stage instead of waiting for documents a
     * tenancy that began on paper cannot produce.
     */
    @Column(nullable = false, length = 20)
    private String origin;

    /**
     * The rent frozen at birth (6.1/6.2), in the payment's actual money. The
     * calendar bills this figure forever; a pricing re-versioned mid-life never
     * re-prices a running tenancy. Null only for a pricing-born figure the
     * birth could not read, which the schedule then answers from the pricing.
     */
    @Column(name = "rent_amount", precision = 15, scale = 2)
    private java.math.BigDecimal rentAmount;

    /**
     * Whether this tenancy owes the condition stage, frozen at birth from the
     * pricing it was born on (V38).
     * <p>
     * <b>A term, not a setting.</b> The pricing's own flag is editable in place
     * and is the answer to "what is offered from now on"; this column is the
     * answer to "what does this tenant owe". Reading it from the pricing — as
     * activation, the repair report, the settlement gate and
     * {@code bypassesConditions} all did — let an owner add or remove a
     * contractual stage on a running tenancy by editing a price.
     */
    @Column(nullable = false)
    private boolean checkable;

    /**
     * The billing cadence, frozen at birth from the pricing (V39): the calendar
     * derives its periods from this row, never from the pricing's live pointer.
     * <p>
     * An offer is editable — {@code updatePricing} repoints its duration — and
     * the schedule is generated long after signing, so reading the pricing here
     * meant an owner could turn a monthly tenancy into a yearly one mid-life
     * while the rent stayed frozen. This is the cadence the tenant signed.
     */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "duration_id", nullable = false)
    private Duration duration;

    /**
     * The content revision the client sees ({@code LeaseResponse.version}):
     * birth is one and every accepted edit increments it. It is <b>not</b> a
     * concurrency token — it cannot tell a double ACCEPT from two sequential
     * edits, and it cannot stop a payment being fired twice.
     */
    @Column(nullable = false)
    private Integer version;

    /**
     * Hibernate's concurrency token, and the reason a tenancy cannot be moved
     * twice at once: every UPDATE carries {@code AND lock_version = <loaded>},
     * so two ACCEPTs (or an END racing the vigil's {@code closeIfDue}) leave the
     * loser's write affecting no row — {@code OptimisticLockException}, answered
     * as 409 by the shared handler — instead of both passing the status check
     * and both firing their side effects. Internal: never returned to clients.
     * See V37.
     */
    @jakarta.persistence.Version
    @Column(name = "lock_version", nullable = false)
    private Integer lockVersion;

    @Column(name = "created_by")
    private UUID createdBy;
}
