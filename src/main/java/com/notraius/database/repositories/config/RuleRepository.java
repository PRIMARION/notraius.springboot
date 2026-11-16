package com.notraius.database.repositories.config;

import com.notraius.database.entities.config.Rule;
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
public interface RuleRepository extends JpaRepository<Rule, UUID> {

    long countByCreatorIdInAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            Collection<UUID> owners, Instant from, Instant to);

    @Query("SELECT COUNT(r) FROM Rule r WHERE r.creatorId IN :owners " +
            "AND r.status.code = :statusCode")
    long countActiveByCreators(@Param("owners") Collection<UUID> owners,
                               @Param("statusCode") String statusCode);

    /**
     * The catalogue page, with the visibility invariant pushed into SQL rather
     * than applied afterwards (PLAN.md 3.1: "filtering happens before results
     * are shaped, not as a post-filter" — a private rule must never leak
     * through a count or a search suggestion).
     * <p>
     * {@code viewerId} is the caller and {@code staff} widens the scope to
     * everything for moderation. Otherwise a row is visible when it is PUBLIC
     * or when the caller created it.
     * <p>
     * Free text is the trigger-maintained {@code vector} of V35, asked through
     * {@code tsvector_matches} (HQL cannot write Postgres' {@code @@}) — the
     * same full-text predicate as every other searchable table in the estate.
     */
    @EntityGraph(attributePaths = {"status"})
    @Query("""
            SELECT r FROM Rule r
            WHERE (:staff = TRUE OR r.visibility = com.notraius.database.entities.config.RuleVisibility.PUBLIC
                   OR r.creatorId = :viewerId)
              AND (:ownOnly = FALSE OR r.creatorId = :viewerId)
              AND (:statusCode IS NULL OR r.status.code = :statusCode)
              AND (:visibility IS NULL OR r.visibility = :visibility)
              AND (:search IS NULL
                   OR tsvector_matches(r.vector, CAST(:search AS string)) = TRUE)
            """)
    Page<Rule> search(@Param("viewerId") UUID viewerId,
                      @Param("staff") boolean staff,
                      @Param("ownOnly") boolean ownOnly,
                      @Param("statusCode") String statusCode,
                      @Param("visibility") com.notraius.database.entities.config.RuleVisibility visibility,
                      @Param("search") String search,
                      Pageable pageable);

}
