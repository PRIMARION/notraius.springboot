package com.notraius.database.entities.visit;

import com.notraius.database.entities.asset.Property;
import com.notraius.database.entities.reference.Status;
import com.notraius.database.entities.reference.Type;
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

/**
 * What a visit costs (DATABASE.txt 4.1). One row per (property, kind): a
 * property offers one PHYSICAL and one VIRTUAL visit price, exactly as it has
 * one monthly and one nightly rent.
 * <p>
 * <b>Money follows the property.</b> The row carries no currency: the visit it
 * sells is priced in the property's currency, and a property's currency is
 * derived from its location once and frozen (PLAN 4.1), so the money is always
 * well-defined without a second column that could disagree.
 * <p>
 * <b>Time is plain minutes.</b> A visit is a slice of somebody's time, not a
 * rentable unit with a calendar cadence, so {@code durationMinutes} is an
 * integer and deliberately not a reference into the duration catalogue.
 */
@Entity
@Table(name = "pricings", schema = "visit")
@Getter
@Setter
public class VisitPricing extends BaseEntity {

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    /** PHYSICAL or VIRTUAL — a type under the VISIT_PRICINGS entity. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "type_id", nullable = false)
    private Type type;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    /** How long one visit lasts, in minutes. */
    @Column(name = "duration_minutes", nullable = false)
    private Integer durationMinutes;

    @Column(nullable = false)
    private Integer version;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "status_id", nullable = false)
    private Status status;

    @Column(name = "created_by")
    private java.util.UUID createdBy;
}
