package com.notraius.database.repositories.lease.visit;

import com.notraius.database.entities.lease.visit.VisitPricing;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VisitPricingRepository extends JpaRepository<VisitPricing, UUID> {

    @EntityGraph(attributePaths = {"property", "property.status", "type", "status"})
    List<VisitPricing> findByPropertyId(UUID propertyId);

    @EntityGraph(attributePaths = {"property", "property.status", "type", "status"})
    Optional<VisitPricing> findById(UUID pricingId);

    /** The one-row law's read side: one physical and one virtual price per property. */
    boolean existsByPropertyIdAndTypeId(UUID propertyId, UUID typeId);

    /** The property's delete gate: a published visit price keeps the property alive. */
    boolean existsByPropertyId(UUID propertyId);

    @Query("SELECT COUNT(v) FROM VisitPricing v WHERE v.property.ownerId IN :owners " +
            "AND v.createdAt >= :from AND v.createdAt < :to")
    long countNewByPropertyOwners(@Param("owners") Collection<UUID> owners,
                                  @Param("from") Instant from,
                                  @Param("to") Instant to);

    @Query("SELECT COUNT(v) FROM VisitPricing v WHERE v.property.ownerId IN :owners " +
            "AND v.status.code = :statusCode")
    long countActiveByPropertyOwners(@Param("owners") Collection<UUID> owners,
                                     @Param("statusCode") String statusCode);
}
