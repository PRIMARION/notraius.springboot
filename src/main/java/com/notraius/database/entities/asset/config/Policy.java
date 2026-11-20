package com.notraius.database.entities.asset.config;

import com.notraius.database.entities.lease.reference.amenity.Entities;
import com.notraius.database.entities.lease.reference.status.Status;
import com.notraius.database.entities.lease.reference.status.Type;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import com.notraius.database.entities.asset.config.PolicyUnit;

/**
 * A policy — an admin standard (PLAN.md 3.2): "for this kind of thing, here is
 * the allowed range and whether you must comply at all".
 * <p>
 * What it governs is the pair (entity, type): ADVANCE is a policy of type
 * INSTALLMENT, which is why the lease machinery derives its split behaviour
 * from type and unit alone with no hardcoded ADVANCE logic. Where it governs is
 * {@link #stateId} — a Benin standard and a Nigerian one are different rows,
 * and a null state is the stateless fallback the resolution rule in Part 4.3
 * falls back to.
 * <p>
 * <b>Governance is the strictest in the module:</b> the admin defines it, sets
 * the bounds, the mandatory flag, the unit and the status; every user may read
 * it and nothing else. Users pick VALUES against it in the asset module, never
 * standards.
 * <p>
 * <b>Pause is not destruction.</b> Disabling stops the standard being offered
 * and enforced for new configurations; values already set against it stay (they
 * are a property's history) and lose only the ability to be changed while the
 * standard is paused. Re-enabling restores enforcement and re-evaluates the
 * stored values: a value still inside the bounds passes silently, one the admin
 * has since tightened out of range is flagged "needs review" on the property and
 * is <b>never</b> auto-corrected, because the plan does not rewrite a user's
 * number behind their back.
 */
@Entity
@Table(name = "policies", schema = "notraius",
        // The code's identity is (entity, code, state) — a Benin ADVANCE and a
        // Nigerian ADVANCE are two rows. It is enforced by the migration's two
        // PARTIAL unique indexes (one for the stateless row, one per state),
        // which JPA cannot express, so the service is the read-side guard.
        indexes = {
                @Index(name = "idx_policies_entity_id", columnList = "entity_id"),
                @Index(name = "idx_policies_type_id", columnList = "type_id"),
                @Index(name = "idx_policies_status_id", columnList = "status_id"),
                @Index(name = "idx_policies_state_id", columnList = "state_id"),
                @Index(name = "idx_policies_code", columnList = "code")
        })
@Getter
@Setter
public class Policy extends BaseEntity {

    /** The namespace of the standard (POLICIES' own entity binding). */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "entity_id", nullable = false)
    private Entities entity;

    /** What kind of thing the standard governs (ADVANCE → INSTALLMENT). */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "type_id", nullable = false)
    private Type type;

    @Column(nullable = false, length = 100)
    private String title;

    /** Stable identity, unique inside the entity, immutable once used. */
    @Column(nullable = false, length = 50)
    private String code;

    @Column(columnDefinition = "text")
    private String description;

    /** Lower bound; null means unbounded below. */
    @Column(name = "min", precision = 15, scale = 2)
    private BigDecimal minimum;

    /** Upper bound; null means unbounded above. */
    @Column(name = "max", precision = 15, scale = 2)
    private BigDecimal maximum;

    /**
     * Whether compliance is required: a mandatory policy must carry a value on
     * every configuration it governs before that configuration may be
     * published. Flipping it mid-life backfills the applicable values at the
     * minimum instead of blocking publication by mere absence.
     */
    @Column(nullable = false)
    private boolean mandatory;

    /** UNITS or AMOUNT — fixed at birth; it changes how values are read. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12, updatable = false)
    private PolicyUnit unit;

    /** Birth is version one; every edit increments it. */
    @Column(nullable = false)
    private Integer version;

    /** Geographical scope (a state id from the shared lookup); null = fallback. */
    @Column(name = "state_id")
    private UUID stateId;

    /** ENABLED or DISABLED. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "status_id", nullable = false)
    private Status status;

    @Column(name = "created_by")
    private UUID createdBy;

    /**
     * The search column of V35: the database owns it — a BEFORE INSERT OR
     * UPDATE trigger rebuilds it from this row's prose — so it is mapped
     * read-only and exists only so JPQL can hand it to
     * {@code tsvector_matches(vector, :search)}. The application never reads
     * its contents and never writes it.
     */
    @Column(name = "vector", insertable = false, updatable = false, columnDefinition = "tsvector")
    private String vector;
}
