package com.notraius.database.entities.reference;

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

import java.util.UUID;

/**
 * A type: a labelled code bound to an entity — "this kind of row belongs to
 * that kind of thing" (PLAN.md Part 1).
 * <p>
 * The catalogue lives in {@code config.types} — configuration, not marketplace
 * reference data — and the {@code entity} binding is the namespace: the
 * property's kind is a type under {@code PROPERTY}, a pricing cadence a type
 * under {@code PRICINGS}, a time unit a type under {@code DURATION_UNIT}, a
 * reaction kind under {@code REACTIONS}, a spec grouping under {@code SPEC}.
 * Two rows may share a code across entities; the same code twice under one
 * entity is the same type and is refused.
 * <p>
 * The hierarchy is self-referential through {@code type_id} ({@link #parent});
 * a null parent is a root. Parent links always mean "kind of" and never carry
 * their own type — the catalogue has one axis.
 */
@Entity
@Table(name = "types", schema = "config",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_types_entity_code", columnNames = {"entity_id", "code"}),
        indexes = {
                @Index(name = "idx_types_entity_id", columnList = "entity_id"),
                @Index(name = "idx_types_parent_id", columnList = "type_id"),
                @Index(name = "idx_types_status_id", columnList = "status_id")
        })
@Getter
@Setter
public class Type extends BaseEntity {

    /** Namespace: whose kinds these are (PROPERTY, PRICINGS, DURATION_UNIT, …). */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "entity_id", nullable = false)
    private Entities entity;

    /** Parent kind; null at a root. Matches DATABASE.txt 1.2's `type_id`. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "type_id")
    private Type parent;

    /** Machine-meaningful, unique inside its entity, immutable once used. */
    @Column(nullable = false, length = 50)
    private String code;

    /** What humans read; renamable at any time. */
    @Column(nullable = false, length = 100)
    private String label;

    @Column(columnDefinition = "text")
    private String description;

    /** ACTIVE or RETIRED — the catalogue's lifecycle lives in the status row. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "status_id", nullable = false)
    private Status status;

    /** Creator of record; null for admin-migrated vocabulary. */
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
