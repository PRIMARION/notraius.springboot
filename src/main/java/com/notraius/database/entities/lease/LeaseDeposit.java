package com.notraius.database.entities.lease;

import com.notraius.database.entities.config.Policy;
import com.notraius.database.entities.reference.Status;
import com.notraius.database.entities.reference.Type;
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

/**
 * One deposit standard's counter on one lease (DATABASE.txt 5.2 + PLAN 6.4).
 * <p>
 * <b>{@code balance} is the counter, never a price.</b> For a UNITS standard it
 * counts units of the bound pricing's duration (four months of advance); for an
 * AMOUNT standard it counts the whole month-equivalents the frozen figure
 * covered at birth — floored once, logged, and never re-divided however the
 * rent later re-versions (6.4). The money itself lives in exactly one place:
 * the born payment's escrows, mirrored per split in
 * {@link LeaseDepositSplit}; a second price column here could drift from
 * custody, so it does not exist.
 * <p>
 * {@code policyId} pins which standard the counter answers, because a code can
 * be priced differently per state and the lease must keep reading the row its
 * birth resolved, not today's winner of the chain.
 */
@Entity
@Table(name = "deposits", schema = "lease",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_lease_deposits_lease_policy", columnNames = {"lease_id", "policy_id"}),
        indexes = {
                @Index(name = "idx_lease_deposits_lease_id", columnList = "lease_id"),
                @Index(name = "idx_lease_deposits_status_id", columnList = "status_id")
        })
@Getter
@Setter
public class LeaseDeposit extends BaseEntity {

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "lease_id", nullable = false)
    private Lease lease;

    /** The standard whose unit gives the counter its meaning. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "policy_id", nullable = false)
    private Policy policy;

    /** The policy-bank type (ADVANCE, SECURITY, ELECTRICITY). */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "type_id", nullable = false)
    private Type type;

    /** The counter, in the policy's unit. Zero is a spent cushion, not an error. */
    @Column(precision = 15, scale = 2, nullable = false)
    private BigDecimal balance;

    /**
     * The standard this deposit was born against, in the same unit as
     * {@link #balance} (6.5). The notice engine asks whether the counter reached
     * <em>this</em> figure — the standard of the lease's birth, never the live
     * policy — so a mid-life edit to the owner's number cannot re-price a
     * running tenancy's grace. Null on rows born before the column existed.
     */
    @Column(name = "standard_balance", precision = 15, scale = 2)
    private BigDecimal standardBalance;

    /** HELD, RELEASED (consumed as rent) or RETURNED (refunded at settlement). */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "status_id", nullable = false)
    private Status status;

    @Column(nullable = false)
    private Integer version;
}
