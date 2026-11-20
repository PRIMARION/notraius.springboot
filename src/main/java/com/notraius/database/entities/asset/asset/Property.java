package com.notraius.database.entities.asset.asset;

import com.notraius.database.entities.lease.reference.status.Status;
import com.notraius.database.entities.lease.reference.status.Type;
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
 * A property — the asset every money flow of this domain hangs from (PLAN.md
 * 4.1).
 * <p>
 * <b>Two anchors, born immutable.</b> {@link #locationId} points opaquely into
 * the logistics platform's location rows; the state it resolves to is stored
 * once as {@link #stateId}; the currency that state implies is stored once as
 * {@link #currencyId}. From the moment the location is set, none of the three
 * ever changes — no edit path exists at all. That is what makes every amount
 * beneath the property (pricings, policy values, later deposits and visits) mean
 * one thing forever: a number that means francs can never come to mean naira.
 * <p>
 * <b>Save first.</b> The title is the only required content; everything else
 * (type, location, pricings, terms) is optional while the row is a DRAFT. The
 * completeness gates live on publication, not on saving — a half-built property
 * is a legal object, a half-built property in the market is not.
 * <p>
 * <b>Ownership.</b> {@link #ownerId} is the single truth of ownership: the visit
 * module's owner-settlement rule pays whoever this field says, not whoever
 * happened to be interacting with the visit.
 */
@Entity
@Table(name = "properties", schema = "property", indexes = {
        @Index(name = "idx_properties_user_id", columnList = "user_id"),
        @Index(name = "idx_properties_status_id", columnList = "status_id"),
        @Index(name = "idx_properties_type_id", columnList = "type_id"),
        @Index(name = "idx_properties_state_id", columnList = "state_id"),
        @Index(name = "idx_properties_location_id", columnList = "location_id"),
        @Index(name = "idx_properties_currency_id", columnList = "currency_id")
})
@Getter
@Setter
public class Property extends BaseEntity {

    /** The only required content — the rest may arrive later, as a draft. */
    @Column(nullable = false, length = 255)
    private String title;

    /** The owner, full stop (never the interacting actor). */
    @Column(name = "user_id", nullable = false)
    private UUID ownerId;

    /** DRAFT / PUBLISHED / PAUSED / SUSPENDED / DELETED. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "status_id", nullable = false)
    private Status status;

    /** The kind of place — a type under the PROPERTY entity; null while drafting. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "type_id")
    private Type type;

    /** Derived from the resolved state at backend level; never chosen, never edited. */
    @Column(name = "currency_id")
    private UUID currencyId;

    @Column(columnDefinition = "text")
    private String description;

    /** Opaque reference into the logistics platform's location rows. */
    @Column(name = "location_id")
    private UUID locationId;

    /** The state the location resolved to — the key of the policy chain (4.3). */
    @Column(name = "state_id")
    private UUID stateId;

    /**
     * The content revision the client sees: birth is version one, every
     * accepted edit increments it. It is <b>not</b> a concurrency token — two
     * editors who read revision 4 both write 5, and the number cannot say
     * whose terms were lost.
     */
    @Column(nullable = false)
    private Integer version;

    /**
     * Hibernate's concurrency token: every UPDATE carries
     * {@code AND lock_version = <loaded>}, so an edit racing another edit
     * affects no row and raises {@code OptimisticLockException}, which the
     * shared handler answers as 409 CONFLICT. It is what makes "every accepted
     * edit is a new truth" true even under concurrent staff moderation. See
     * V37. Internal: never returned to clients.
     */
    @jakarta.persistence.Version
    @Column(name = "lock_version", nullable = false)
    private Integer lockVersion;

    /** Creator of record (the owner, unless staff created it for them). */
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
