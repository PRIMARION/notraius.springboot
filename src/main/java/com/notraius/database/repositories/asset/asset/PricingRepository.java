package com.notraius.database.repositories.asset.asset;

import com.notraius.database.entities.asset.asset.Pricing;
import org.springframework.data.domain.Pageable;
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
import com.notraius.database.entities.asset.asset.PropertyPolicyValue;
import com.notraius.database.entities.asset.config.Policy;

@Repository
public interface PricingRepository extends JpaRepository<Pricing, UUID> {

    @EntityGraph(attributePaths = {"property", "type", "duration", "validity", "status"})
    List<Pricing> findByPropertyId(UUID propertyId);

    @Query("SELECT COUNT(p) FROM Pricing p WHERE p.property.ownerId IN :owners " +
            "AND p.createdAt >= :from AND p.createdAt < :to")
    long countNewByPropertyOwners(@Param("owners") Collection<UUID> owners,
                                  @Param("from") Instant from,
                                  @Param("to") Instant to);

    /**
     * A pricing is active only while it answers now: LIVE and inside its
     * half-open validity window. A stored LIVE status past its toDate reads
     * EXPIRED and must not count.
     */
    @Query("""
            SELECT COUNT(p) FROM Pricing p
            WHERE p.property.ownerId IN :owners
              AND p.status.code = :liveStatusCode
              AND p.validity.fromDate <= :now
              AND (p.validity.toDate IS NULL OR p.validity.toDate > :now)
            """)
    long countActiveByPropertyOwners(@Param("owners") Collection<UUID> owners,
                                     @Param("liveStatusCode") String liveStatusCode,
                                     @Param("now") Instant now);

    /** One pricing per (property, type) — the one-row law's read side. */
    boolean existsByPropertyIdAndTypeId(UUID propertyId, UUID typeId);

    boolean existsByPropertyId(UUID propertyId);

    /** Reference-catalogue gates: a bound type/duration cannot be deleted. */
    boolean existsByTypeId(UUID typeId);

    boolean existsByDurationId(UUID durationId);

    /**
     * How many of the property's pricings answer <em>now</em> — the publication
     * gate's question ("at least one pricing whose window is live"). The window
     * is half-open, so an end instant equal to now is already expired.
     */
    @Query("""
            SELECT COUNT(p) FROM Pricing p
            WHERE p.property.id = :propertyId
              AND p.status.code = :liveStatusCode
              AND p.validity.fromDate <= :now
              AND (p.validity.toDate IS NULL OR p.validity.toDate > :now)
            """)
    long countAnsweringNow(@Param("propertyId") UUID propertyId,
                           @Param("liveStatusCode") String liveStatusCode,
                           @Param("now") Instant now);

    /**
     * The property's pricings that have no value for a policy (mandatory backfill),
     * one keyset page at a time in id order. A standard flipped mandatory can
     * govern every pricing in a state — a million of them — so the backfill walks
     * id-ordered pages and seeds each page before reading the next, instead of
     * hydrating the whole set at once.
     */
    @EntityGraph(attributePaths = {"property", "type", "duration", "validity", "status"})
    @Query("""
            SELECT pr FROM Pricing pr
            WHERE (:stateId IS NULL OR pr.property.stateId = :stateId)
              AND pr.id > :afterId
              AND NOT EXISTS (SELECT 1 FROM PropertyPolicyValue v
                              WHERE v.pricing = pr AND v.policy.id = :policyId)
            ORDER BY pr.id ASC
            """)
    List<Pricing> findMissingValueForPolicyPage(@Param("policyId") UUID policyId,
                                                @Param("stateId") UUID stateId,
                                                @Param("afterId") UUID afterId,
                                                Pageable pageable);

    /**
     * The stateless standard's backfill set: every pricing in a world that has no
     * standard of its own under this code. A state-scoped row shadows the global
     * default; it never doubles it.
     */
    @EntityGraph(attributePaths = {"property", "type", "duration", "validity", "status"})
    @Query("""
            SELECT pr FROM Pricing pr
            WHERE (pr.property.stateId IS NULL OR NOT EXISTS (
                     SELECT 1 FROM Policy po
                     WHERE po.code = :code
                       AND po.stateId = pr.property.stateId
                       AND po.status.code = :enabledStatusCode))
              AND pr.id > :afterId
              AND NOT EXISTS (SELECT 1 FROM PropertyPolicyValue v
                              WHERE v.pricing = pr AND v.policy.id = :policyId)
            ORDER BY pr.id ASC
            """)
    List<Pricing> findMissingValueForStatelessPolicyPage(@Param("code") String code,
                                                        @Param("policyId") UUID policyId,
                                                        @Param("enabledStatusCode") String enabledStatusCode,
                                                        @Param("afterId") UUID afterId,
                                                        Pageable pageable);

    Optional<Pricing> findByIdAndPropertyId(UUID pricingId, UUID propertyId);

    /** The pricing whose window covers an instant, for one property and type. */
    @EntityGraph(attributePaths = {"property", "type", "duration", "validity", "status"})
    @Query("""
            SELECT p FROM Pricing p
            WHERE p.property.id = :propertyId
              AND p.type.id = :typeId
              AND p.validity.fromDate <= :instant
              AND (p.validity.toDate IS NULL OR p.validity.toDate > :instant)
            """)
    Optional<Pricing> findAnsweringAt(@Param("propertyId") UUID propertyId,
                                      @Param("typeId") UUID typeId,
                                      @Param("instant") Instant instant);
}
