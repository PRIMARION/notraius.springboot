package com.notraius.database.repositories.visit;

import com.notraius.database.entities.visit.VisitPricing;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
