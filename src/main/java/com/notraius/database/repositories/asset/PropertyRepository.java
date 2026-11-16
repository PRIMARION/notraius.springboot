package com.notraius.database.repositories.asset;

import com.notraius.database.entities.asset.Property;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

@Repository
public interface PropertyRepository extends JpaRepository<Property, UUID> {

    long countByOwnerIdInAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            Collection<UUID> owners, Instant from, Instant to);

    @Query("SELECT COUNT(p) FROM Property p WHERE p.ownerId IN :owners " +
            "AND p.status.code IN :statusCodes")
    long countActiveByOwners(@Param("owners") Collection<UUID> owners,
                             @Param("statusCodes") Collection<String> statusCodes);

    /**
     * The property page. Every filter is optional and pushed into SQL, so a
     * count, a page and a detail read always agree about what exists — the
     * market page asks for {@code statusCode=PUBLISHED}, the owner's page for
     * its own {@code ownerId} with no status restriction.
     * <p>
     * Free text is the trigger-maintained {@code vector} of V35, asked with
     * {@code plainto_tsquery} as the contract module and V1's own searchable
     * tables do — a phrase matches the words in it, in any order, instead of
     * having to appear verbatim as a substring. HQL cannot write Postgres'
     * {@code @@} operator, so the predicate goes through
     * {@code tsvector_matches(vector, query)
     * (V35)}, the function the planner inlines into the GIN index scan.
     */
    @EntityGraph(attributePaths = {"status", "type"})
    @Query("""
            SELECT p FROM Property p
            WHERE (:ownerId IS NULL OR p.ownerId = :ownerId)
              AND (:statusCode IS NULL OR p.status.code = :statusCode)
              AND (:typeId IS NULL OR p.type.id = :typeId)
              AND (:stateId IS NULL OR p.stateId = :stateId)
              AND (:search IS NULL
                   OR tsvector_matches(p.vector, CAST(:search AS string)) = TRUE)
            """)
    Page<Property> search(@Param("ownerId") UUID ownerId,
                          @Param("statusCode") String statusCode,
                          @Param("typeId") UUID typeId,
                          @Param("stateId") UUID stateId,
                          @Param("search") String search,
                          Pageable pageable);

    /**
     * The market page's optional {@code ?sort=popular} door: the identical
     * filter contract, ordered by how many actors' impressions each row has
     * collected (the engagement module's grouped popularity fact), freshness
     * the tie-break. Zero-impression rows still appear — the LEFT-join shape
     * of the correlated count — so popularity reorders, never filters.
     * <p>
     * The caller passes an <b>unsorted</b> Pageable: the order lives here, so
     * Spring cannot append a second {@code ORDER BY} over it.
     */
    @EntityGraph(attributePaths = {"status", "type"})
    @Query(value = """
            SELECT p FROM Property p
            WHERE (:statusCode IS NULL OR p.status.code = :statusCode)
              AND (:typeId IS NULL OR p.type.id = :typeId)
              AND (:stateId IS NULL OR p.stateId = :stateId)
              AND (:search IS NULL
                   OR tsvector_matches(p.vector, CAST(:search AS string)) = TRUE)
            ORDER BY (SELECT COUNT(i.id) FROM Impression i
                      WHERE i.referenceId = p.id AND i.entityId = :entityId) DESC,
                     p.createdAt DESC, p.id ASC
            """,
            countQuery = """
            SELECT COUNT(p) FROM Property p
            WHERE (:statusCode IS NULL OR p.status.code = :statusCode)
              AND (:typeId IS NULL OR p.type.id = :typeId)
              AND (:stateId IS NULL OR p.stateId = :stateId)
              AND (:search IS NULL
                   OR tsvector_matches(p.vector, CAST(:search AS string)) = TRUE)
            """)
    Page<Property> searchPopular(@Param("statusCode") String statusCode,
                                 @Param("entityId") UUID entityId,
                                 @Param("typeId") UUID typeId,
                                 @Param("stateId") UUID stateId,
                                 @Param("search") String search,
                                 Pageable pageable);

    /** The type delete gate: a property's kind keeps its type row alive. */
    boolean existsByTypeId(UUID typeId);
}
