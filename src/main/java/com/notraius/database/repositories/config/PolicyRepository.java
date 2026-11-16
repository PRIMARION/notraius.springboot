package com.notraius.database.repositories.config;

import com.notraius.database.entities.config.Policy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface PolicyRepository extends JpaRepository<Policy, UUID> {

    /**
     * The catalogue page: every filter optional, each parameter also compared to
     * a typed path so a null argument's type is inferable. Free text is the
     * trigger-maintained {@code vector} of V35, asked through
     * {@code tsvector_matches} — HQL cannot write Postgres' {@code @@}, and a
     * native query would lose the entity graph and the pageable sort.
     */
    @EntityGraph(attributePaths = {"entity", "type", "status"})
    @Query("""
            SELECT p FROM Policy p
            WHERE (:entityCode IS NULL OR p.entity.code = :entityCode)
              AND (:typeId IS NULL OR p.type.id = :typeId)
              AND (:stateId IS NULL OR p.stateId = :stateId)
              AND (:statusCode IS NULL OR p.status.code = :statusCode)
              AND (:search IS NULL
                   OR tsvector_matches(p.vector, CAST(:search AS string)) = TRUE)
            """)
    Page<Policy> search(@Param("entityCode") String entityCode,
                        @Param("typeId") UUID typeId,
                        @Param("stateId") UUID stateId,
                        @Param("statusCode") String statusCode,
                        @Param("search") String search,
                        Pageable pageable);

    /**
     * The code identity is (entity, code, state): the SAME code may govern Benin
     * and Nigeria with different bounds and even a different unit — that is the
     * whole point of the state scoping of 4.3.
     */
    long countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(Instant from, Instant to);

    long countByStatus_Code(String statusCode);

    boolean existsByEntityIdAndCodeAndStateId(UUID entityId, String code, UUID stateId);

    /** The stateless slot of that identity — one global default per code. */
    boolean existsByEntityIdAndCodeAndStateIdIsNull(UUID entityId, String code);

    /** The type's delete gate: a policy bound to a type keeps it alive. */
    boolean existsByTypeId(UUID typeId);

    /**
     * The resolution candidates for a property's state (PLAN.md 4.3): the
     * standards scoped TO that state plus the stateless fallbacks. The winner
     * per code is picked by the caller — a state-specific row shadows the
     * stateless row of the same code, never the other way round.
     */
    @EntityGraph(attributePaths = {"entity", "type", "status"})
    @Query("""
            SELECT p FROM Policy p
            WHERE (:stateId IS NULL OR p.stateId IS NULL OR p.stateId = :stateId)
              AND p.status.code = :statusCode
            ORDER BY p.code ASC
            """)
    List<Policy> findCandidates(@Param("stateId") UUID stateId,
                                @Param("statusCode") String statusCode);

    /**
     * Whether the state has its OWN standard under this code — the question that
     * decides if the stateless row may answer as a fallback at all.
     */
    @Query("""
            SELECT COUNT(p) FROM Policy p
            WHERE p.code = :code AND p.stateId = :stateId AND p.status.code = :statusCode
            """)
    long countStateSpecific(@Param("code") String code,
                            @Param("stateId") UUID stateId,
                            @Param("statusCode") String statusCode);
}
