package com.notraius.database.repositories.asset;

import com.notraius.database.entities.asset.PropertyRuleSelection;
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
public interface PropertyRuleSelectionRepository extends JpaRepository<PropertyRuleSelection, UUID> {

    @EntityGraph(attributePaths = {"property", "rule", "rule.status", "validity", "validity.status"})
    List<PropertyRuleSelection> findByPropertyId(UUID propertyId);

    Optional<PropertyRuleSelection> findByIdAndPropertyId(UUID selectionId, UUID propertyId);

    @Query("SELECT COUNT(s) FROM PropertyRuleSelection s " +
            "WHERE s.property.ownerId IN :owners " +
            "AND s.createdAt >= :from AND s.createdAt < :to")
    long countNewByPropertyOwners(@Param("owners") Collection<UUID> owners,
                                  @Param("from") Instant from,
                                  @Param("to") Instant to);

    /** A selection applies only while its status is ACTIVE and its window is open. */
    @Query("""
            SELECT COUNT(s) FROM PropertyRuleSelection s
            WHERE s.property.ownerId IN :owners
              AND s.validity.status.code = :activeStatusCode
              AND s.validity.fromDate <= :now
              AND (s.validity.toDate IS NULL OR s.validity.toDate > :now)
            """)
    long countActiveByPropertyOwners(@Param("owners") Collection<UUID> owners,
                                     @Param("activeStatusCode") String activeStatusCode,
                                     @Param("now") Instant now);

    /** The rule's in-use lock: a live property selection keeps its status honest. */
    boolean existsByRuleId(UUID ruleId);

    /**
     * The orphan test: does <i>another person's</i> property rely on the rule?
     * The creator's own properties are the creator's to affect, so they do not
     * block a PUBLIC → PRIVATE flip.
     */
    boolean existsByRuleIdAndPropertyOwnerIdNot(UUID ruleId, UUID ownerId);

    boolean existsByPropertyId(UUID propertyId);
}
