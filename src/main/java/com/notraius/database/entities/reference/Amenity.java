package com.notraius.database.entities.reference;

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

import java.util.UUID;

/**
 * An amenity: what a place has (PLAN.md Part 3.3). The catalogue is moderated,
 * so this row carries a lifecycle rather than a boolean flag:
 * <ul>
 *   <li>PENDING — proposed by a user, awaiting an admin decision</li>
 *   <li>ACTIVE — live; properties may select it</li>
 *   <li>REJECTED / CANCELLED — the proposal ended without becoming live</li>
 *   <li>DISABLED — switched off by an admin; reversible</li>
 *   <li>RETIRED — out of the catalogue for good</li>
 * </ul>
 * <p>
 * <b>Who owns the row changes over time.</b> While PENDING it belongs to its
 * {@link #proposer}, who may withdraw it. The moment it is live the ownership
 * is the platform's: only an admin edits it, changes its status, disables or
 * retires it — the proposer's rights evaporate.
 * <p>
 * <b>Code uniqueness is scoped to ACTIVE rows</b>, not to the table: two
 * users may propose "Pool" in parallel and both proposals live in PENDING.
 * The uniqueness of the live catalogue is enforced in the service (a partial
 * unique index cannot be expressed through JPA metadata and the check must
 * also run when an amenity is re-enabled).
 */
@Entity
@Table(name = "amenities", schema = "notraius",
        indexes = {
                @Index(name = "idx_amenities_type_id", columnList = "type_id"),
                @Index(name = "idx_amenities_status_id", columnList = "status_id"),
                @Index(name = "idx_amenities_code", columnList = "code"),
                @Index(name = "idx_amenities_user_id", columnList = "user_id")
        })
@Getter
@Setter
public class Amenity extends BaseEntity {

    /** The amenity's kind — a type under the AMENITIES entity. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "type_id", nullable = false)
    private Type type;

    /** Immutable after birth: a different type is a different amenity. */
    @Enumerated(EnumType.STRING)
    @Column(name = "data_type", nullable = false, length = 12, updatable = false)
    private AmenityDataType dataType;

    /** Machine identity, unique among ACTIVE rows, immutable once used. */
    @Column(nullable = false, length = 50)
    private String code;

    /** What humans read; the admin may rename it at any time. */
    @Column(nullable = false, length = 100)
    private String label;

    @Column(columnDefinition = "text")
    private String description;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "status_id", nullable = false)
    private Status status;

    /**
     * The proposer. Null when an admin created the row directly — staff birth
     * is staff approval, so such a row is born ACTIVE and never had a
     * proposal to attribute.
     */
    @Column(name = "user_id")
    private UUID proposer;

    /** Who performed the birth — the proposer, or an admin on a direct birth. */
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
