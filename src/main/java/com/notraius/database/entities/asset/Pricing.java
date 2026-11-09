package com.notraius.database.entities.asset;

import com.notraius.database.entities.reference.Duration;
import com.notraius.database.entities.reference.Status;
import com.notraius.database.entities.reference.Type;
import com.notraius.database.entities.reference.Validity;
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
 * A pricing — "what does one unit of this property cost, and when does that
 * answer stand" (PLAN.md 4.2).
 * <p>
 * <b>One row per (property, type) for the life of the property.</b> The
 * operator's one-pricing law is the unique constraint, not a convention: a
 * property's nightly row exists once and every future nightly price is a later
 * state of that same row. A price change therefore bumps {@link #version} — and
 * when the price must answer again after its window closed, it also receives a
 * fresh {@link #validity}.
 * <p>
 * <b>Support is derived, never flagged.</b> The property supports nightly iff it
 * has a nightly row whose window contains now; removing the row or letting its
 * window end withdraws the system by itself, so a separate "supported" flag can
 * never drift out of sync with reality.
 * <p>
 * <b>The status has two persisted values.</b> LIVE and PAUSED are acts (the
 * clock's EXPIRED is derived from {@link #validity} at read time), because a
 * stored EXPIRED would let a nightly sweep and a request disagree about the same
 * instant.
 * <p>
 * <b>The amount is always the property's currency</b> — there is no currency
 * column here on purpose: the unit is inherited, so a pricing can never disagree
 * with its property about what a number means.
 */
@Entity
@Table(name = "pricings", schema = "property",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_pricings_property_type", columnNames = {"property_id", "type_id"}),
        indexes = {
                @Index(name = "idx_pricings_property_id", columnList = "property_id"),
                @Index(name = "idx_pricings_type_id", columnList = "type_id"),
                @Index(name = "idx_pricings_duration_id", columnList = "duration_id"),
                @Index(name = "idx_pricings_validity_id", columnList = "validity_id"),
                @Index(name = "idx_pricings_status_id", columnList = "status_id")
        })
@Getter
@Setter
public class Pricing extends BaseEntity {

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    /** The commercial kind (NIGHTLY, MONTHLY) — a type under PRICINGS. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "type_id", nullable = false)
    private Type type;

    /** The physics of one unit (ten hours, one calendar month). */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "duration_id", nullable = false)
    private Duration duration;

    /** Strictly positive, in the property's currency (zero is an aberration). */
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    /**
     * Whether a lease born on this pricing runs the move-in/move-out condition
     * stage of Part 6.2, or bypasses it entirely.
     */
    @Column(nullable = false)
    private boolean checkable;

    @Column(nullable = false)
    private Integer version;

    /** The window in which this price answers; permanent when {@code toDate} is null. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "validity_id", nullable = false)
    private Validity validity;

    /** LIVE or PAUSED — EXPIRED is the window's verdict, never stored. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "status_id", nullable = false)
    private Status status;

    @Column(name = "created_by")
    private UUID createdBy;
}
