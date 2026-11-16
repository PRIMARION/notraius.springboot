package com.notraius.database.repositories.reference;

import com.notraius.database.entities.reference.Type;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface TypeRepository extends JpaRepository<Type, UUID> {

    /**
     * The catalogue page. Every filter is optional; each parameter is also
     * compared to a typed path, which is what lets Hibernate infer a null
     * argument's type instead of failing on an untyped literal. Free text is
     * the trigger-maintained {@code vector} of V35, asked through
     * {@code tsvector_matches} — HQL cannot write Postgres' {@code @@}, and a
     * native query would lose the entity graph and the pageable sort.
     */
    @EntityGraph(attributePaths = {"entity", "status"})
    @Query("""
            SELECT t FROM Type t
            WHERE (:entityCode IS NULL OR t.entity.code = :entityCode)
              AND (:parentId IS NULL OR t.parent.id = :parentId)
              AND (:statusCode IS NULL OR t.status.code = :statusCode)
              AND (:search IS NULL
                   OR tsvector_matches(t.vector, CAST(:search AS string)) = TRUE)
            """)
    Page<Type> search(@Param("entityCode") String entityCode,
                      @Param("parentId") UUID parentId,
                      @Param("statusCode") String statusCode,
                      @Param("search") String search,
                      Pageable pageable);

    boolean existsByEntityIdAndCode(UUID entityId, String code);

    /** One catalogue row by its namespace and code — the lease's deposit types. */
    java.util.Optional<Type> findByEntityCodeAndCode(String entityCode, String code);

    /** The live rows of one namespace — the reaction kinds the client may offer. */
    java.util.List<Type> findByEntityCodeAndStatusCodeOrderByCodeAsc(
            String entityCode, String statusCode);

    /** Children of a node — the delete gate and the reparent cycle walk. */
    boolean existsByParentId(UUID parentId);
}
