package com.notraius.database.repositories.reference;

import com.notraius.database.entities.reference.Amenity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AmenityRepository extends JpaRepository<Amenity, UUID> {

    /**
     * The row's transition lock. Cancellation races the admin's decision, and
     * PLAN.md 3.3 settles the race by first-writer-wins: both sides read the
     * row FOR UPDATE inside their own transaction, so whichever commits first
     * owns the outcome and the other sees a non-PENDING row and fails cleanly.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Amenity a WHERE a.id = :id")
    Optional<Amenity> findByIdForUpdate(@Param("id") UUID id);

    /*
     * The three list queries carry an entity graph (type + status are shown on
     * every row) and ask free text through {@code tsvector_matches} — HQL cannot
     * write Postgres' {@code @@}, and a native query would lose the entity
     * graph and the pageable sort. The locking finder deliberately has no
     * graph: FOR UPDATE cannot fence the nullable side of the outer join an
     * entity graph introduces.
     */

    /** The live catalogue — the only rows a property may select. */
    @EntityGraph(attributePaths = {"type", "status"})
    @Query("""
            SELECT a FROM Amenity a
            WHERE a.status.id = :statusId
              AND (:typeId IS NULL OR a.type.id = :typeId)
              AND (:search IS NULL
                   OR tsvector_matches(a.vector, CAST(:search AS string)) = TRUE)
            """)
    Page<Amenity> findLive(@Param("statusId") UUID statusId,
                           @Param("typeId") UUID typeId,
                           @Param("search") String search,
                           Pageable pageable);

    /** The moderation queue: everything that is not live, whatever its status. */
    @EntityGraph(attributePaths = {"type", "status"})
    @Query("""
            SELECT a FROM Amenity a
            WHERE a.status.code <> :liveStatusCode
              AND (:statusCode IS NULL OR a.status.code = :statusCode)
              AND (:typeId IS NULL OR a.type.id = :typeId)
              AND (:search IS NULL
                   OR tsvector_matches(a.vector, CAST(:search AS string)) = TRUE)
            """)
    Page<Amenity> findSubmissions(@Param("liveStatusCode") String liveStatusCode,
                                  @Param("statusCode") String statusCode,
                                  @Param("typeId") UUID typeId,
                                  @Param("search") String search,
                                  Pageable pageable);

    /** A user's own submissions, whatever their status. */
    @EntityGraph(attributePaths = {"type", "status"})
    @Query("""
            SELECT a FROM Amenity a
            WHERE a.proposer = :userId
              AND (:statusCode IS NULL OR a.status.code = :statusCode)
              AND (:typeId IS NULL OR a.type.id = :typeId)
              AND (:search IS NULL
                   OR tsvector_matches(a.vector, CAST(:search AS string)) = TRUE)
            """)
    Page<Amenity> findMine(@Param("userId") UUID userId,
                           @Param("statusCode") String statusCode,
                           @Param("typeId") UUID typeId,
                           @Param("search") String search,
                           Pageable pageable);

    /**
     * Code uniqueness among ACTIVE rows — the catalogue rule, not the
     * table's. {@code excludeId} lets a row be re-checked against itself
     * (confirm, enable); null means "count every live row".
     * <p>
     * Written out rather than derived so the case-insensitive comparison is
     * visible: a code is a machine identity, and POOL/pool must not become two
     * live amenities.
     */
    @Query("""
            SELECT COUNT(a) FROM Amenity a
            WHERE UPPER(a.code) = UPPER(:code)
              AND a.status.id = :statusId
              AND (:excludeId IS NULL OR a.id <> :excludeId)
            """)
    long countLiveByCode(@Param("code") String code,
                         @Param("statusId") UUID statusId,
                         @Param("excludeId") UUID excludeId);

    /** The type's delete gate. */
    boolean existsByTypeId(UUID typeId);
}
