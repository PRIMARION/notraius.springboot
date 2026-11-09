package com.notraius.database.repositories.asset;

import com.notraius.database.entities.asset.PropertyPolicyValue;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PropertyPolicyValueRepository extends JpaRepository<PropertyPolicyValue, UUID> {

    @EntityGraph(attributePaths = {"pricing", "pricing.property", "pricing.type", "policy", "policy.type",
            "policy.entity", "policy.status", "status"})
    List<PropertyPolicyValue> findByPricingId(UUID pricingId);

    @EntityGraph(attributePaths = {"pricing", "policy", "status"})
    Optional<PropertyPolicyValue> findByPricingIdAndPolicyId(UUID pricingId, UUID policyId);

    /** Every value of the given pricings — one read for a publication gate. */
    @EntityGraph(attributePaths = {"policy", "status"})
    List<PropertyPolicyValue> findByPricingIdIn(Collection<UUID> pricingIds);

    /** The pricing's delete gate: the owner's numbers are the property's story. */
    boolean existsByPricingId(UUID pricingId);

    /**
     * The admin's disable/enable cascade, one keyset page at a time in id order: pausing a standard
     * can touch every property's commitment in the country, so the pass walks
     * id-ordered pages and writes each before reading the next.
     */
    @EntityGraph(attributePaths = {"pricing", "pricing.property", "policy", "status"})
    @Query("""
            SELECT v FROM PropertyPolicyValue v
            WHERE v.policy.id = :policyId
              AND v.id > :afterId
            ORDER BY v.id ASC
            """)
    List<PropertyPolicyValue> findByPolicyIdPage(@Param("policyId") UUID policyId,
                                                 @Param("afterId") UUID afterId,
                                                 Pageable pageable);

    /** How many values point at a pricing (the count-only form of the gate). */
    @Query("SELECT COUNT(v) FROM PropertyPolicyValue v WHERE v.pricing.id = :pricingId")
    long countByPricing(@Param("pricingId") UUID pricingId);
}
