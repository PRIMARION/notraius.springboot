package com.notraius.database.repositories.asset.asset;

import com.notraius.database.entities.asset.asset.PropertyAmenity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PropertyAmenityRepository extends JpaRepository<PropertyAmenity, UUID> {

    @EntityGraph(attributePaths = {"amenity", "amenity.type", "amenity.status", "status"})
    List<PropertyAmenity> findByPropertyId(UUID propertyId);

    @EntityGraph(attributePaths = {"amenity", "amenity.status", "status"})
    Optional<PropertyAmenity> findByPropertyIdAndAmenityId(UUID propertyId, UUID amenityId);

    /**
     * The disable/enable cascade's set, one keyset page at a time in id order:
     * switching a catalogue amenity off can suppress every holding in the
     * country, so the pass walks id-ordered pages and writes each before reading
     * the next.
     */
    @EntityGraph(attributePaths = {"property", "amenity", "status"})
    @Query("""
            SELECT h FROM PropertyAmenity h
            WHERE h.amenity.id = :amenityId
              AND h.id > :afterId
            ORDER BY h.id ASC
            """)
    List<PropertyAmenity> findByAmenityIdPage(@Param("amenityId") UUID amenityId,
                                              @Param("afterId") UUID afterId,
                                              Pageable pageable);

    /** The catalogue's in-use gate: a held amenity cannot be deleted. */
    boolean existsByAmenityId(UUID amenityId);
}
