package com.notraius.database.entities.asset.asset;

import com.notraius.database.entities.lease.reference.amenity.Amenity;
import com.notraius.database.entities.lease.reference.status.Status;
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
import com.notraius.database.entities.asset.asset.Property;

/**
 * One amenity a property holds, with the value the amenity's data type demands
 * ("pool: true", "parking: 2", "wifi: 24/7") — PLAN.md 3.3 / 4.1.
 * <p>
 * <b>Two switches, one column.</b> The status distinguishes the OWNER's own
 * switch ({@code DISABLED}) from the CATALOGUE's ({@code SUPPRESSED}). That
 * distinction is the whole cascade law: disabling the amenity globally must
 * suppress the holdings without erasing the owner's deliberate choices, so that
 * re-enabling the amenity restores exactly what the catalogue took and never
 * what the owner refused.
 * <p>
 * (property, amenity) is the identity: a property holds an amenity once, and
 * re-setting it edits the same row.
 */
@Entity
@Table(name = "amenities", schema = "property",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_property_amenities_pair", columnNames = {"property_id", "amenity_id"}),
        indexes = {
                @Index(name = "idx_property_amenities_property_id", columnList = "property_id"),
                @Index(name = "idx_property_amenities_amenity_id", columnList = "amenity_id"),
                @Index(name = "idx_property_amenities_status_id", columnList = "status_id")
        })
@Getter
@Setter
public class PropertyAmenity extends BaseEntity {

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "amenity_id", nullable = false)
    private Amenity amenity;

    /** The holding's value, already validated against the amenity's data type. */
    @Column(length = 255)
    private String value;

    /** ACTIVE / DISABLED (owner) / SUPPRESSED (catalogue). */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "status_id", nullable = false)
    private Status status;

    @Column(name = "created_by")
    private UUID createdBy;
}
